@file:OptIn(com.feedme.storage.SessionControlRecoveryCompositionApi::class)

package com.feedme.development.progress

import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.system.Os
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.feedme.app.mealflow.MealFlowExperience
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import com.feedme.session.*
import com.feedme.storage.*
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Run this entire class on a FRESH isolated progress UID. The first method positively claims
 * absence; later methods never infer permission to erase an existing installation. Real app,
 * native stores, runtime, queue and service ledger; explicitly SYNTHETIC identity/catalog/service.
 * Reopening here is an acknowledged local close/reopen, not an OS process-kill durability claim. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AndroidProgressHostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val application get() = context.applicationContext as ProgressApplication
    private val owner get() = application.owner
    private val ui get() = instrumentation.uiAutomation
    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main.immediate) { block() } }
    private fun <T> value(result: PortResult<T>): T = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> error("Expected acknowledged progress result: ${result.reason}")
    }
    private fun failed(result: PortResult<*>, reason: FailureReason? = null) {
        assertTrue("Expected failure, never inferred success", result is PortResult.Failure)
        reason?.let { assertEquals(it, (result as PortResult.Failure).reason) }
    }
    private fun requireClaim() { check(claimed && retained == null && duplicateRetained == null) { "Fresh isolated fixture claim required" } }
    private fun evidenceInventory(): Pair<Boolean, Set<String>> {
        val base = File(context.applicationInfo.dataDir, "no_backup") // Not the creating getter.
        return base.exists() to KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList().toSet()
    }
    private suspend fun startOwner() {
        if (owner.states.value.phase == ProgressHostPhase.CLOSED) value(owner.inspectStartup())
        if (owner.states.value.phase == ProgressHostPhase.RESUME_AVAILABLE) value(owner.resume())
        assertEquals(ProgressHostPhase.START_AVAILABLE, owner.states.value.phase)
        value(owner.start()); assertEquals(ProgressHostPhase.ACTIVE, owner.states.value.phase)
    }
    private suspend fun resetOwner() {
        if (owner.states.value.phase == ProgressHostPhase.CLOSED) value(owner.inspectStartup())
        if (owner.states.value.phase == ProgressHostPhase.RESUME_AVAILABLE) value(owner.resume())
        if (owner.states.value.phase == ProgressHostPhase.START_AVAILABLE) { value(owner.close()); return }
        check(owner.states.value.canReset) { "Never erase an unverified or partial owner" }
        owner.requestReset()
        val ticket = checkNotNull(owner.states.value.confirmation)
        value(owner.confirmReset(ticket)); assertEquals(ProgressHostPhase.CLOSED, owner.states.value.phase)
    }
    private suspend fun meal(experience: MealFlowExperience) {
        value(experience.refreshContext())
        experience.edit { it.copy(mode = MealMode.ASSEMBLE, energy = MealEnergy.ASSEMBLE, servings = "1",
            totalMinutes = "5", ingredientIds = listOf(ProgressCatalog.ingredientId), equipmentIds = listOf("bowl"),
            tasteTags = listOf("crunch")) }
        value(experience.findMeal()); value(experience.recipe())
    }

    @Test fun aFreshProbeNeverCreatesFilesKeysOrNormalSession() {
        assertEquals(ProgressNativeInventory.PACKAGE, context.packageName)
        assertEquals(ProgressHostPhase.NEW, main { owner.states.value.phase })
        val before = evidenceInventory()
        assertTrue("Fresh UID required; no fixture cleanup of existing material", before.second.isEmpty())
        assertEquals(ProgressInventoryKind.FRESH, main { value(ProgressNativeInventory.inspect(context)) })
        val foreign = object : ContextWrapper(context) { override fun getPackageName() = "invalid.progress.fixture" }
        main { failed(ProgressNativeInventory.inspect(foreign)); value(owner.inspectStartup()) }
        assertEquals(before, evidenceInventory())
        main {
            assertEquals(ProgressHostPhase.START_AVAILABLE, owner.states.value.phase)
            assertNull(owner.boundary.current()); assertNull(owner.experience.value)
            value(owner.close()); assertEquals(ProgressHostPhase.CLOSED, owner.states.value.phase)
        }
        claimed = true // Only positive absence and acknowledged close establish this test-process claim.
    }

    @Test fun bActualLauncherRequiresConsentAndRetainsCookingAcrossActivityRecreation() {
        requireClaim()
        val scenario = ActivityScenario.launch<ProgressActivity>(Intent(context, ProgressActivity::class.java))
        try {
            click("Inspect retained state"); waitText("Start preview"); screenshot("progress-start")
            click("Start preview")
            await { main { owner.states.value.phase == ProgressHostPhase.ACTIVE && owner.experience.value?.forms?.value?.busy == false } }
            val experience = main { checkNotNull(owner.experience.value) }
            waitText("What’s the"); screenshot("progress-request")
            main { meal(experience) }
            val expectedPlan = main { checkNotNull(experience.meals.states.value.plan).plan.id.value }
            click("Cook this plan"); waitText("Start this exact plan?"); screenshot("progress-confirmation")
            val ticket = main { checkNotNull(experience.cookingNavigation.value.confirmation) }
            main { assertNull(experience.cooking.states.value.cooking) }
            scenario.recreate(); waitText("Start this exact plan?")
            main { assertSame(experience, owner.experience.value); assertSame(ticket, experience.cookingNavigation.value.confirmation) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { textNodes("Start this exact plan?").isEmpty() }
            main {
                assertSame(experience, owner.experience.value)
                assertTrue(experience.cookingNavigation.value.visible)
                assertNull(experience.cookingNavigation.value.confirmation)
                assertEquals(CookingFlowPhase.START_CONFIRMATION, experience.cooking.states.value.phase)
                assertEquals(expectedPlan, experience.cooking.states.value.plan!!.id.value)
                assertNull(experience.cooking.states.value.cooking)
                assertEquals(CookbookScreen.HIDDEN, experience.cookbook.states.value.screen)
            }
            // Exercise scrolling before returning to the exact prepared action. A compact
            // screen may still show Review at its bottom; being offscreen is not a UX contract.
            // Scrolling must neither prepare a replacement nor start the retained plan.
            scrollToEnd()
            main {
                assertSame(experience, owner.experience.value)
                assertEquals(expectedPlan, experience.cooking.states.value.plan!!.id.value)
                assertEquals(CookingFlowPhase.START_CONFIRMATION, experience.cooking.states.value.phase)
                assertNull(experience.cookingNavigation.value.confirmation)
                assertNull(experience.cooking.states.value.cooking)
            }
            click("Review prepared start"); click("Confirm cooking start")
            await { main { experience.cooking.states.value.phase == CookingFlowPhase.COOKING && !experience.forms.value.busy } }
            main {
                assertEquals(expectedPlan, experience.cooking.states.value.plan!!.id.value)
                assertTrue(experience.cooking.states.value.serverAcknowledged)
            }
            waitText("One step at a time."); screenshot("progress-cooking")
            // The host's permanent banner is actual app content, not an instrumentation overlay.
            waitText("Preview · demo data")
        } catch (failure: Throwable) {
            // Capture the actual failing route BEFORE reset. These synthetic-fixture diagnostics
            // are separate from accepted captures and never turn a failure into a passing test.
            try { failureDiagnostics() } catch (diagnosticFailure: Throwable) { failure.addSuppressed(diagnosticFailure) }
            throw failure
        } finally { scenario.close(); main { resetOwner() } }
    }

    @Test fun cAcknowledgedCloseAndResumeKeepExactLocalDraftCookingAndServiceLineage() {
        requireClaim()
        val phases = mutableListOf<String>()
        fun mark(operation: String) {
            val state = owner.states.value // Called only inside the existing Main blocks.
            phases += "$operation: phase=${state.phase}, busy=${state.busy}, canReset=${state.canReset}, canRetryRetirement=${state.canRetryRetirement}"
        }
        var primary: Throwable? = null
        try {
            main {
                mark("start.before")
                startOwner(); mark("start.after")
                val experience = checkNotNull(owner.experience.value)
                value(experience.restore()); mark("restore.after")
                meal(experience); mark("meal.after")
                value(experience.prepareCooking()); mark("prepare.after")
                value(experience.confirmCooking(checkNotNull(experience.cookingNavigation.value.confirmation))); mark("confirm.after")
                val plan = checkNotNull(experience.cooking.states.value.plan).id.value
                mark("close.before")
                value(owner.close()); mark("close.after")
                assertNull(owner.experience.value); assertNull(experience.forms.value.values)
                value(owner.inspectStartup()); mark("inspect.after")
                assertEquals(ProgressHostPhase.RESUME_AVAILABLE, owner.states.value.phase)
                value(owner.resume()); mark("resume.after")
                val restored = checkNotNull(owner.experience.value); assertNotSame(experience, restored)
                value(restored.restore()); mark("restored-read.after")
                assertEquals(plan, restored.cooking.states.value.plan!!.id.value)
                assertTrue(restored.cooking.states.value.historical)
                assertFalse(restored.cooking.states.value.serverAcknowledged)
                assertEquals(listOf(ProgressCatalog.ingredientId), restored.meals.states.value.draft!!.ingredientIds)
                // The local synthetic service must read its existing encrypted ledger. It may
                // not create replacement history on restore. A real GET validates this old pin.
                value(restored.cooking.refresh()); mark("refresh.after")
                assertEquals(plan, restored.cooking.states.value.plan!!.id.value)
                assertFalse(restored.cooking.states.value.serverAcknowledged) // A GET is not a new mutation acknowledgement.
                mark("body.complete")
            }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                main {
                    mark("cleanup.before")
                    try { resetOwner() } finally { mark("cleanup.after") }
                }
            } catch (cleanup: Throwable) {
                val original = primary
                if (original != null) original.addSuppressed(cleanup)
                else { primary = cleanup; throw cleanup }
            } finally {
                // JUnit retains suppressed exceptions in the existing instrumentation log.
                // Only fixed operation names, public phase enums and booleans are recorded.
                primary?.addSuppressed(IllegalStateException("Progress c phase trace: " + phases.joinToString(" -> ")))
            }
        }
    }

    @Test fun dRestoredIdentityWithoutServiceLedgerIsBlockedButExactResetRemainsAvailable() {
        requireClaim()
        val seed = NativeSeed(); retained = seed
        try { main { seed.open(); seed.createIdentityWithoutService(); seed.close(); retained = null } }
        catch (failure: Throwable) { main { seed.close(); retained = null }; throw failure }
        try {
            main {
                value(owner.inspectStartup()); assertEquals(ProgressHostPhase.RESUME_AVAILABLE, owner.states.value.phase)
                failed(owner.resume(), FailureReason.NOT_FOUND)
                assertEquals(ProgressHostPhase.RECOVERY_REQUIRED, owner.states.value.phase)
                assertNull(owner.experience.value); assertNotNull(owner.boundary.current())
                assertTrue(owner.states.value.canReset); assertFalse(owner.states.value.canRetryCreate)
                failed(owner.start(), FailureReason.CONFLICT) // Missing service is never fresh-install evidence.
                resetOwner()
            }
        } finally { main { if (owner.states.value.phase != ProgressHostPhase.CLOSED) value(owner.close()) } }
    }

    @Test fun eActualPendingSetupRoutesOnlyToOwnedInspectionAndExplicitOriginalAbort() {
        requireClaim()
        val seed = NativeSeed(); retained = seed
        try { main { seed.open(); seed.preparePendingSetup(); seed.close(); retained = null
            assertEquals(ProgressInventoryKind.EXISTING, value(ProgressNativeInventory.inspect(context))) } }
        catch (failure: Throwable) { main { seed.close(); retained = null }; throw failure }
        try {
            main {
                value(owner.inspectStartup()); assertEquals(ProgressHostPhase.SETUP_RECOVERY, owner.states.value.phase)
                assertNull(owner.boundary.current()); assertNull(owner.experience.value)
                failed(owner.start(), FailureReason.CONFLICT); failed(owner.resume(), FailureReason.CONFLICT)
                value(owner.openSetupRecovery()); assertTrue(owner.states.value.recoveryOpen)
                value(owner.prepareSetupAbort()); val old = checkNotNull(owner.states.value.confirmation)
                owner.dismissConfirmation(); assertNull(owner.boundary.current())
                value(owner.prepareSetupAbort()); assertNotSame(old, owner.states.value.confirmation)
                value(owner.confirmSetupAbort(checkNotNull(owner.states.value.confirmation)))
                assertEquals(ProgressHostPhase.CLOSED, owner.states.value.phase); assertNull(owner.boundary.current())
                assertFalse(checkNotNull(seed.pendingCandidate).exists()) // Removed only by authenticated original-plan abort.
                value(owner.inspectStartup()); assertEquals(ProgressHostPhase.RESUME_AVAILABLE, owner.states.value.phase)
                value(owner.resume()); assertEquals(ProgressHostPhase.START_AVAILABLE, owner.states.value.phase)
                assertNull(owner.experience.value); value(owner.close())
            }
        } finally { main { if (owner.states.value.phase != ProgressHostPhase.CLOSED) value(owner.close()) } }
    }

    @Test fun fCancelledAcquireAndInjectedPreCloseFailureRetainActualOwnerUntilRetry() {
        requireClaim()
        val acquired = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var injected = false; var beforeClose = 0
        val held = ProgressSessionOwner(context) { stage ->
            if (stage == ProgressNativeStage.CONTROL_ACQUIRED) { acquired.complete(Unit); release.await() }
            if (stage == ProgressNativeStage.BEFORE_CONTROL_CLOSE) {
                beforeClose++; if (!injected) { injected = true; error("Test-only pre-close failure") }
            }
        }
        retained = held
        try {
            main {
                value(held.inspectStartup()); assertEquals(ProgressHostPhase.RESUME_AVAILABLE, held.states.value.phase)
                coroutineScope {
                    val opening = launch { held.resume() }
                    withTimeout(10_000) { acquired.await() }; opening.cancelAndJoin()
                }
                assertEquals(ProgressHostPhase.CLOSE_ONLY, held.states.value.phase)
                assertNull(held.experience.value); assertNull(held.boundary.current())
                val duplicate = AndroidSessionControlStore.createRecoveryStore(context)
                duplicateRetained = duplicate
                failed(duplicate.open()); value(duplicate.close()); duplicateRetained = null
                failed(held.close(), FailureReason.STORAGE_FAILURE); assertEquals(1, beforeClose)
                assertEquals(ProgressHostPhase.CLOSE_ONLY, held.states.value.phase)
                value(held.close()); assertEquals(2, beforeClose); retained = null
                // This is a BEFORE-close injected fault, not simulated successful/ambiguous OS close.
                value(owner.inspectStartup()); value(owner.resume())
                assertEquals(ProgressHostPhase.START_AVAILABLE, owner.states.value.phase); value(owner.close())
            }
        } finally { release.complete(Unit); main { value(held.close()); retained = null } }
    }

    /** Native prerequisite fixture only. No replacement verifier/store/service implementation.
     * Every acquired owner is retained before later I/O, and only actual acknowledged close drops it. */
    private inner class NativeSeed {
        private val boundary = SessionBoundary()
        private var composition: SessionApplicationComposition? = null
        private var reservation: SessionCompositionReservation? = null
        private var control: EncryptedSessionControlStore? = null
        private var work: EncryptedSessionWorkStore? = null
        private var data: EncryptedStateDatabase? = null
        private var credentials: AndroidCredentialStore? = null
        private var runtime: PrivateSessionRuntime? = null
        private var planner: SessionWorkRegistry? = null
        private var acquisitionUncertain = false
        var pendingCandidate: File? = null
            private set
        suspend fun open() {
            assertEquals(ProgressHostPhase.CLOSED, owner.states.value.phase)
            assertEquals(ProgressInventoryKind.EXISTING, value(ProgressNativeInventory.inspect(context)))
            composition = value(SessionApplicationComposition.create(boundary, Dispatchers.Main.immediate, ProgressIdentity.configurationBinding))
            reservation = value(composition!!.reserve())
            acquire({ AndroidSessionControlStore.open(context) }) { control = it }
            assertEquals(SessionStartupControlKind.TERMINAL, value(SessionStartupControlInspector.inspect(control!!)))
            acquire({ AndroidSessionWorkStore.open(context) }) { work = it }
            acquire({ AndroidStateDatabase.open(context) }) { data = it }
            acquire({ AndroidCredentialStore.open(context) }) { credentials = it }
        }
        suspend fun createIdentityWithoutService() {
            acquire({ PrivateSessionRuntime.openReserved(reservation!!, control!!, work!!, data!!, credentials!!,
                ProgressIdentity, NativeWorkCancellationPort { PortResult.Failure(FailureReason.NOT_CONFIGURED) },
                NativeWorkIdSource { UUID.randomUUID().toString() },
                NativeWorkExecutionPolicy { _, _, _, _ -> PortResult.Failure(FailureReason.NOT_CONFIGURED) }) }) { runtime = it }
            assertEquals(PrivateSessionPhase.SIGNED_OUT, value(runtime!!.recover()))
            val access = value(runtime!!.create()); assertEquals(ProgressIdentity.scope, access.scope)
            // Deliberately no ProgressCanonicalService construction/open in this prerequisite.
        }
        suspend fun preparePendingSetup() {
            val identity = value(ProgressIdentity.acquire())
            val credential = value(credentials!!.planCreate(value(credentials!!.state()).revision, identity))
            val activation = value(data!!.planActivation(ProgressIdentity.scope))
            acquire({ SessionWorkRegistry.open(work!!, boundary, Dispatchers.Main.immediate,
                NativeWorkCancellationPort { PortResult.Failure(FailureReason.NOT_CONFIGURED) }, NativeWorkIdSource { UUID.randomUUID().toString() },
                NativeWorkAdmissionPolicy { PortResult.Failure(FailureReason.NOT_CONFIGURED) },
                NativeWorkExecutionPolicy { _, _, _, _ -> PortResult.Failure(FailureReason.NOT_CONFIGURED) }) }) { planner = it }
            val workPlan = value(planner!!.planOrigin(ProgressIdentity.scope, value(work!!.read())!!.revision))
            // Test-only exact local codec fixture; production app sees only the trusted classifier.
            val raw = buildJsonObject {
                put("version", 1); put("purpose", "session-setup"); put("operationId", UUID.randomUUID().toString())
                put("scope", buildJsonObject { put("environment", ProgressIdentity.scope.environment)
                    put("actorKind", ProgressIdentity.scope.actorKind.name); put("actorId", ProgressIdentity.scope.actorId) })
                put("configurationBinding", ProgressIdentity.configurationBinding)
                put("credentialPlan", hex(credential.copyForStorage().copyForCodec()))
                put("dataPlan", hex(activation.copyForStorage())); put("workOriginPlan", hex(workPlan.copyForStorage().copyForCodec()))
            }.toString().encodeToByteArray()
            val plan = value(SessionSetupPlan.fromStorage(PrivateBytes(raw)))
            val pending = PrivateBytes(buildJsonObject {
                put("version", 1); put("state", "session-setup-pending")
                put("plan", hex(plan.copyForStorage().copyForCodec())); put("abortRequested", false)
            }.toString().encodeToByteArray())
            val before = value(control!!.read())!!
            val changed = value(control!!.compareAndSet(before.revision, pending))
            assertEquals(before.revision + 1, changed.revision)
            assertArrayEquals(pending.copyForCodec(), changed.payload.copyForCodec())
            val reread = value(control!!.read())!!; assertEquals(changed.revision, reread.revision)
            assertArrayEquals(pending.copyForCodec(), reread.payload.copyForCodec())
            assertEquals(SessionStartupControlKind.PENDING_SETUP, value(SessionStartupControlInspector.inspect(control!!)))
            assertNull(boundary.current())
            // Synthetic partial-artifact fixture, not an induced OS crash: copy an existing
            // encrypted manifest to the native plan's exact bounded candidate name. Neither
            // this file name nor the coarse inventory is authority to delete anything.
            val details = Json.parseToJsonElement(credential.copyForStorage().copyForCodec().decodeToString()).jsonObject
            val incarnation = details.getValue("incarnation").jsonPrimitive.content
            val directory = File(context.applicationInfo.dataDir, "no_backup/feedme-credentials")
            val candidate = File(directory, "manifest.create.$incarnation.pending")
            check(!candidate.exists() && candidate.createNewFile()); pendingCandidate = candidate
            Os.chmod(candidate.path, 384)
            val bytes = File(directory, "manifest.bin").readBytes()
            try { FileOutputStream(candidate).use { it.write(bytes); it.fd.sync() } } finally { bytes.fill(0) }
        }
        suspend fun close() = withContext(NonCancellable + Dispatchers.Main.immediate) {
            planner?.let { value(it.close()); planner = null }; runtime?.let { value(it.close()); runtime = null }
            work?.let { value(it.close()); work = null }; data?.let { value(it.close()); data = null }
            credentials?.let { value(it.close()); credentials = null }; control?.let { value(it.close()); control = null }
            // A legacy returning factory may retain an owner that it could not return. This
            // fixture cannot acknowledge its close: retain the seed/root/reservation forever
            // in this process rather than claiming successful cleanup or starting another test.
            check(!acquisitionUncertain) { "Unreturned native acquisition: fixture ownership retained" }
            reservation?.let { value(it.release()); reservation = null }; composition?.let { value(it.close()); composition = null }
        }
        private suspend fun <T> acquire(open: suspend () -> PortResult<T>, retain: (T) -> Unit) {
            currentCoroutineContext().ensureActive()
            check(!acquisitionUncertain)
            acquisitionUncertain = true
            withContext(NonCancellable) { retain(value(open())); acquisitionUncertain = false }
            currentCoroutineContext().ensureActive()
        }
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun await(check: () -> Boolean) {
        repeat(100) { instrumentation.waitForIdleSync(); if (check()) return; SystemClock.sleep(100) }
        fail("Timed out waiting for exact progress host state")
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { found += node; repeat(node.childCount) { node.getChild(it)?.let(::visit) } }
        ui.rootInActiveWindow?.let(::visit); return found
    }
    private fun textNodes(text: String) = nodes().filter {
        it.text?.toString()?.contains(text) == true || it.contentDescription?.toString()?.contains(text) == true
    }
    private fun waitText(text: String) = await { textNodes(text).any { it.isVisibleToUser } }
    private fun scrollToEnd() {
        repeat(12) {
            instrumentation.waitForIdleSync()
            val scrollable = nodes().firstOrNull { it.isScrollable && it.isVisibleToUser } ?: return
            if (!scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return
            SystemClock.sleep(100) // Pace real accessibility scrolling, not a cooking-state wait.
        }
    }
    private fun click(text: String) {
        fun exact(node: AccessibilityNodeInfo) = node.text?.toString() == text || node.contentDescription?.toString() == text
        fun clickVisible(snapshot: List<AccessibilityNodeInfo>): Boolean {
            for (node in snapshot.filter { it.isVisibleToUser && exact(it) }) {
                var action: AccessibilityNodeInfo? = node
                while (action != null && !action.isClickable) action = action.parent
                val button = action ?: continue
                if (!node.refresh() || !exact(node) || !node.isVisibleToUser || !button.refresh() || !button.isVisibleToUser) continue
                assertTrue("Explicit progress action must be enabled: $text", button.isEnabled)
                assertTrue("Explicit progress click must be accepted: $text", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                return true // Never redispatch after an action may have been accepted.
            }
            return false
        }
        // Recreation can retain a viewport below an action. Search back to the top before the
        // bounded forward traversal; scrolling is observation, not another button dispatch.
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            repeat(30) { attempt ->
                instrumentation.waitForIdleSync()
                val snapshot = nodes()
                if (clickVisible(snapshot)) return
                if (attempt % 3 == 2) snapshot.firstOrNull { it.isScrollable && it.isVisibleToUser }?.performAction(direction)
                SystemClock.sleep(100)
            }
        }
        fail("Missing explicit progress action: $text")
    }
    private fun failureDiagnostics() {
        instrumentation.waitForIdleSync()
        val phase = main {
            val current = owner.experience.value
            val cooking = current?.cooking?.states?.value
            val navigation = current?.cookingNavigation?.value
            "host=${owner.states.value.phase}; cooking=${cooking?.phase}; screen=${cooking?.screen}; " +
                "planPresent=${cooking?.plan != null}; pinPresent=${cooking?.cooking != null}; " +
                "pendingCount=${cooking?.pending?.size}; issue=${cooking?.issue}; failure=${cooking?.failureReason}; " +
                "visible=${navigation?.visible}; confirmation=${navigation?.confirmation?.kind}; " +
                "cookbook=${current?.cookbook?.states?.value?.screen}; busy=${current?.forms?.value?.busy}; dirty=${current?.forms?.value?.dirty}"
        }
        val tree = nodes().take(128).joinToString("\n") { node ->
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            "visible=${node.isVisibleToUser}; enabled=${node.isEnabled}; clickable=${node.isClickable}; scrollable=${node.isScrollable}; " +
                "bounds=$bounds; text=${node.text?.toString()?.take(256)}; description=${node.contentDescription?.toString()?.take(256)}"
        }
        val base = context.cacheDir
        val baseStat = Os.lstat(base.path)
        check(android.system.OsConstants.S_ISDIR(baseStat.st_mode) && baseStat.st_uid == android.os.Process.myUid())
        val directory = File(base.canonicalFile, "progress-host-ui-diagnostics")
        check(!directory.exists() && directory.mkdir()); Os.chmod(directory.path, 448)
        val stat = Os.lstat(directory.path)
        check(android.system.OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == android.os.Process.myUid() && (stat.st_mode and 511) == 448)
        val evidence = File(directory, "cooking-consent-failure.accessibility.txt")
        check(evidence.createNewFile()); Os.chmod(evidence.path, 384)
        FileOutputStream(evidence).use { it.write((phase + "\n" + tree).take(64_000).encodeToByteArray()); it.fd.sync() }
        val bitmap = checkNotNull(ui.takeScreenshot())
        try {
            val target = File(directory, "cooking-consent-failure.png")
            check(target.createNewFile()); Os.chmod(target.path, 384)
            FileOutputStream(target).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.fd.sync() }
        } finally { bitmap.recycle() }
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(650); instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(ui.takeScreenshot())
        val directory = File(context.cacheDir, "progress-ui-evidence").also { check(it.mkdirs() || it.isDirectory) }
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    private companion object {
        var claimed = false
        var retained: Any? = null // Retain failures; never hide cleanup loss behind another fixture.
        var duplicateRetained: ExistingSessionControlRecoveryStore? = null
    }
}
