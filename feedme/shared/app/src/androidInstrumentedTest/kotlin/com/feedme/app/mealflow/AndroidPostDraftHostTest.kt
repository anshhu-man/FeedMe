package com.feedme.app.mealflow

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
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
import com.feedme.mealflow.social.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Actual Android host, retained draft controller, shared queue and encrypted native session.
 * Account verification and all five canonical draft operations use an explicitly SYNTHETIC
 * transport. This does not prove HTTP/PG, production login, publication, process death or iOS. */
@RunWith(AndroidJUnit4::class)
class AndroidPostDraftHostTest {
    @get:Rule val testName = TestName()
    private var failureCaptureSequence = 0
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui get() = instrumentation.uiAutomation
    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main.immediate) { block() } }
    private inner class Fixture {
        val session = NativeMealFlowTestSession(instrumentation.targetContext)
        val transport = NativePostDraftTestTransport(session)
        val allocatedIds = mutableListOf<String>()
        var completedTransportCalls = 0
        private val observedTransport = object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> =
                transport.execute(lease, call).also { completedTransportCalls++ }
        }
        var time = session.clock.nowMillis()
        lateinit var owner: NativeMealFlowHostOwner
        lateinit var scenario: ActivityScenario<NativeMealFlowTestActivity>
        val experience get() = owner.experience
        val drafts get() = checkNotNull(experience.postDrafts)
        suspend fun createExperience() = MealFlowExperience.fromSession(
            checkNotNull(session.currentAccess()), observedTransport, session.boundary, session.dispatcher, EpochClock { time },
            ConnectivityPort { Connectivity.ONLINE }, MealOperationIds { UUID.randomUUID().toString().also { allocatedIds += it } },
            MealFlowPolicy(86_400_000, 86_400_000, 5, 100), IngredientPickerPolicy(20, 3, 100, 86_400_000),
            MealInputChoices(listOf(MealInputChoice("bowl", "Synthetic bowl")), emptyList()), KitchenInputPolicy(20, 3, 100, 20, 65_536),
            CookingFlowPolicy(300_000, 65_536, 65_536), CookbookPolicy(20, 60_000),
            PostDraftClientPolicy(8, 1_048_576, 262_144, 64, 64, 20, 100, 60_000))
        fun settled() = await { main { !experience.forms.value.busy && !drafts.states.value.busy } }
        fun recreate() { scenario.recreate(); settled() }
        fun openDrafts() { click("My private drafts"); await { main { drafts.states.value.screen == PostDraftScreen.LOCAL_LIST } }; waitText("Start a fresh draft") }
        fun newDraft(caption: String = "Synthetic kitchen win") {
            openDrafts(); click("Start a fresh draft"); waitText("What did you make?")
            setText("What did you make?", caption)
            await { main { drafts.states.value.selected?.let { it.caption == caption && it.localAcknowledged } == true } }
        }
        fun save() { click("Save privately to server"); await { main { drafts.states.value.serverAcknowledged && !drafts.states.value.busy } } }
        fun allowOriginalRetry() = main { time = maxOf(time + 60_000, drafts.states.value.earliestRetryAtMillis ?: time) }
    }

    private fun withHost(block: (Fixture) -> Unit) {
        val f = Fixture(); var opened = false; var launched = false; var failure: Throwable? = null
        try {
            main {
                check(retainedFixture == null && NativeMealFlowHostOwner.current == null)
                retainedFixture = f // Retain before ANY native setup; failed cleanup retains ownership.
                val result = f.session.open()
                assertTrue("Native draft fixture ${f.session.diagnosticStage}: $result", result is PortResult.Value)
                opened = true; f.owner = NativeMealFlowHostOwner(f.createExperience()); NativeMealFlowHostOwner.current = f.owner
            }
            f.scenario = ActivityScenario.launch(Intent(instrumentation.targetContext, NativeMealFlowTestActivity::class.java))
            launched = true; waitText("What’s the"); f.settled(); block(f)
        } catch (t: Throwable) {
            failure = t
            // Preserve direct assertion failures too, before the host/session is closed.
            // Timeout helpers may already have captured; sequence names keep both originals.
            if (launched) try { failureScreenshot("assertion") } catch (capture: Throwable) { t.addSuppressed(capture) }
            throw t
        }
        finally {
            try {
                main { f.transport.mutationGate?.complete(Unit); f.transport.readGate?.complete(Unit) }
                if (launched) f.scenario.close()
                main {
                    NativeMealFlowHostOwner.current?.let { value(it.experience.close()) }
                    value(if (opened) f.session.retireAndClose() else f.session.close())
                    NativeMealFlowHostOwner.current = null; retainedFixture = null
                }
            } catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    @Test fun realUiLocalTextSurvivesRecreationAndRetainedOwnerReplacementWithoutSending() = withHost { f ->
        f.openDrafts(); screenshot("drafts-local-empty"); click("Start a fresh draft")
        // The intro must leave the actual editable caption reachable without scrolling.
        fullyVisibleWithoutScrolling("What did you make?", editable = true)
        // Accessibility text actions drive the real Compose callbacks, not direct controller edits.
        for (text in listOf("R", "Ra", "Rapid synthetic kitchen win")) setText("What did you make?", text, settle = false)
        setText("Image description (optional)", "Synthetic green bowl")
        await { main { f.drafts.states.value.selected?.let {
            it.caption == "Rapid synthetic kitchen win" && it.altText == "Synthetic green bowl" && it.localAcknowledged
        } == true } }
        val client = main { f.drafts.states.value.selected!!.clientDraftId }
        top(); screenshot("drafts-editor-local"); f.recreate(); waitText("What did you make?")
        main { assertEquals(client, f.drafts.states.value.selected!!.clientDraftId); assertEquals("Synthetic green bowl", f.drafts.states.value.selected!!.altText); assertTrue(f.transport.calls.isEmpty()) }
        back(); waitText("Private on this device"); screenshot("drafts-local-list"); back(); waitText("What’s the")
        main {
            assertTrue(f.transport.calls.isEmpty()); value(f.experience.close())
            f.owner = NativeMealFlowHostOwner(f.createExperience()); NativeMealFlowHostOwner.current = f.owner
        }
        f.recreate(); f.openDrafts(); click("Rapid synthetic kitchen win"); waitText("What did you make?")
        main { assertEquals(client, f.drafts.states.value.selected!!.clientDraftId); assertTrue(f.drafts.states.value.selected!!.localAcknowledged); assertTrue(f.transport.calls.isEmpty()) }
    }

    @Test fun explicitUiCreateUpdateListGetAndConfirmedDeleteUseAllFiveCanonicalOperations() = withHost { f ->
        f.newDraft("Synthetic first caption"); f.save(); waitText("Saved privately on the server", true); screenshot("drafts-server-saved")
        top(); setText("What did you make?", "Synthetic updated caption")
        await { main { f.drafts.states.value.selected?.let { it.caption == "Synthetic updated caption" && it.localAcknowledged } == true } }
        f.save(); main { assertEquals("\"2\"", f.drafts.states.value.selected!!.server!!.etag) }
        back(); waitText("Private on this device"); click("Load server drafts")
        await { main { f.drafts.states.value.remoteItems.size == 1 } }; waitText("Synthetic updated caption", true); screenshot("drafts-server-list")
        click("Synthetic updated caption"); waitText("What did you make?"); f.settled()
        val before = main { f.transport.calls.size }
        click("Review server discard"); waitText("Discard this server draft?"); screenshot("drafts-server-discard-confirmation")
        back(); await { textNodes("Discard this server draft?").isEmpty() }
        main { assertEquals(before, f.transport.calls.size); assertNotNull(f.drafts.states.value.selected) }
        click("Review server discard"); click("Confirm server discard")
        await { main { f.drafts.states.value.let { state -> state.localDrafts.isEmpty() && state.serverAcknowledged &&
            state.screen == PostDraftScreen.LOCAL_LIST && state.selected == null && state.discardConfirmation == null } } }
        await { textNodes("Discard this server draft?").isEmpty() }
        main {
            assertEquals(listOf("createPostDraft", "updatePostDraft", "listPostDrafts", "getPostDraft", "deletePostDraft"), f.transport.calls.map { it.operationId })
            val create = f.transport.calls.first(); val patch = f.transport.calls[1]; val deleted = f.transport.calls.last()
            assertNull(create.ifMatch); assertEquals("\"1\"", patch.ifMatch); assertEquals("\"2\"", deleted.ifMatch)
            assertEquals(patch.pathParameters, deleted.pathParameters); assertNull(deleted.body)
            assertEquals(setOf("caption"), NativePostDraftTestTransport.json(checkNotNull(patch.body)).keys)
        }
        // Successful exact discard returns directly to the list. Do not hide an empty editor
        // with an extra Back or accidentally dismiss a still-closing confirmation dialog.
        waitText("Private on this device", scroll = true); screenshot("drafts-after-server-discard")
    }

    @Test fun localRemovalModalDismissesOnBackAndCannotDeleteAChangedRevision() = withHost { f ->
        f.newDraft("Synthetic reviewed local text")
        click("Remove local draft"); waitText("Remove this local draft?"); screenshot("drafts-local-removal-confirmation")
        back(); await { textNodes("Remove this local draft?").isEmpty() }
        main { assertEquals(1, f.drafts.states.value.localDrafts.size); assertTrue(f.transport.calls.isEmpty()) }
        click("Remove local draft"); waitText("Remove this local draft?")
        val staleButton = clickable("Confirm local removal")
        main { val client = f.drafts.states.value.selected!!.clientDraftId; value(f.drafts.editCaption(client, "Synthetic newer text must remain")) }
        await { textNodes("Remove this local draft?").isEmpty() }
        // Android may reject this detached node. If delivered, the actual callback must recheck.
        staleButton.performAction(AccessibilityNodeInfo.ACTION_CLICK); instrumentation.waitForIdleSync()
        main { assertEquals("Synthetic newer text must remain", f.drafts.states.value.localDrafts.single().caption); assertTrue(f.transport.calls.isEmpty()) }
        click("Remove local draft"); click("Confirm local removal")
        await { main { f.drafts.states.value.localDrafts.isEmpty() } }; waitText("Private on this device")
        main { assertTrue(f.transport.calls.isEmpty()) }
    }

    @Test fun lostSyntheticSaveResponseSurvivesRecreationAndOnlyExplicitRetryUsesOriginalKey() = withHost { f ->
        f.newDraft("Synthetic uncertain save"); main { f.transport.unknownNextMutation = true }
        click("Save privately to server")
        // Pre-dispatch publication is intentionally pending too. Wait for the actual returned
        // synthetic failure AND its observed original queue outcome, not an early UI projection.
        await { main { f.drafts.states.value.let { state ->
            f.completedTransportCalls == 1 && f.transport.calls.size == 1 && !state.busy && !state.serverAcknowledged &&
                state.pending?.let { pending -> pending.phase == "AWAITING_CONFIRMATION" && pending.attempts == 1 &&
                    pending.issue == "OUTCOME_UNKNOWN" && pending.canRetry && !pending.canDiscardUnsent && !pending.finalizationRequired } == true
        } } }
        val original = main { f.transport.calls.single() }; top()
        waitText("Private save is not confirmed", scroll = true)
        assertTrue(textNodes("createPostDraft").isEmpty()); assertTrue(textNodes("AWAITING_CONFIRMATION").isEmpty())
        screenshot("drafts-original-pending")
        f.recreate(); main { assertEquals(1, f.transport.calls.size); assertFalse(f.drafts.states.value.serverAcknowledged) }
        f.allowOriginalRetry(); top(); click("Retry original action")
        await { main { f.drafts.states.value.serverAcknowledged && f.drafts.states.value.pending == null } }
        main {
            val retry = f.transport.calls.last(); assertEquals(2, f.transport.calls.size)
            assertEquals(original.operationId, retry.operationId); assertEquals(original.pathParameters, retry.pathParameters)
            assertEquals(original.idempotencyKey!!.use { it }, retry.idempotencyKey!!.use { it })
            assertArrayEquals(original.body!!.copyForCodec(), retry.body!!.copyForCodec()); assertEquals(original.ifMatch, retry.ifMatch)
        }
        waitText("Saved privately on the server", true); screenshot("drafts-original-reconciled")
    }

    @Test fun backDuringNonCooperativeSaveNeverReopensEditorOrAcknowledgesLateResponse() = withHost { f ->
        f.newDraft("Synthetic retained after Back"); val gate = CompletableDeferred<Unit>()
        main { f.transport.mutationGate = gate }; click("Save privately to server")
        await { main { f.transport.calls.size == 1 } }
        val original = main { f.transport.calls.single().idempotencyKey!!.use { it } }
        back()
        await { main { f.drafts.states.value.screen == PostDraftScreen.LOCAL_LIST } }
        waitText("Retry original action", scroll = true)
        main {
            assertEquals(0, f.completedTransportCalls); assertEquals(1, f.transport.calls.size)
            val pending = checkNotNull(f.drafts.states.value.pending)
            assertEquals(original, pending.commandId); assertFalse(pending.canDiscardUnsent)
            assertFalse(f.drafts.states.value.serverAcknowledged)
        }
        assertTrue(textNodes("Cancel unsent server action").isEmpty())
        main { f.transport.mutationGate = null; gate.complete(Unit) }
        await { main { f.completedTransportCalls == 1 } }; f.settled()
        waitText("Retry original action", scroll = true)
        assertTrue(textNodes("Cancel unsent server action").isEmpty())
        // The pending-action cards push this list heading below the viewport; the exact
        // LOCAL_LIST transition was already observed and remains asserted after scrolling.
        waitText("Private on this device", scroll = true); screenshot("drafts-back-uncertain")
        main {
            assertEquals(PostDraftScreen.LOCAL_LIST, f.drafts.states.value.screen); assertNull(f.drafts.states.value.selected)
            assertFalse(f.drafts.states.value.serverAcknowledged); assertEquals("Synthetic retained after Back", f.drafts.states.value.localDrafts.single().caption)
            assertEquals(1, f.transport.calls.size); assertEquals(1, f.completedTransportCalls)
            val pending = checkNotNull(f.drafts.states.value.pending)
            assertEquals(original, pending.commandId); assertFalse(pending.canDiscardUnsent)
        }
    }

    @Test fun realSessionInvalidationRedactsPrivateUiAndFencesDelayedGet() = withHost { f ->
        f.newDraft("SYNTHETIC_PRIVATE_REDACTION_MARKER"); f.save(); val gate = CompletableDeferred<Unit>()
        main { f.transport.readGate = gate }; click("Refresh server draft")
        await { main { f.transport.calls.lastOrNull()?.operationId == "getPostDraft" } }
        main {
            value(f.session.invalidate()); assertEquals(PostDraftPhase.UNAVAILABLE, f.drafts.states.value.phase)
            assertTrue(f.drafts.states.value.localDrafts.isEmpty()); assertNull(f.drafts.states.value.selected)
            assertNull(f.drafts.states.value.discardConfirmation); assertNull(f.experience.forms.value.values)
            f.transport.readGate = null; gate.complete(Unit)
        }
        top(); waitText("This kitchen is unavailable."); screenshot("drafts-session-redacted")
        assertTrue(textNodes("SYNTHETIC_PRIVATE_REDACTION_MARKER").isEmpty()); assertTrue(textNodes("What did you make?").isEmpty())
        main { assertEquals(2, f.transport.calls.size); assertNull(f.drafts.states.value.selected) }
    }

    @Test fun deniedAssociatedDraftOffersExplicitCleanupWithoutPretendingItIsLocalOnly() = withHost { f ->
        f.newDraft("Synthetic cleanup draft"); f.save(); main { f.transport.denyNextGet = true }
        click("Refresh server draft"); await { main { f.drafts.states.value.selected?.let { it.serverAssociated && it.server == null } == true } }
        waitText("Server content is unavailable. Your local text is retained. Refresh the server status or review discard.", true)
        assertTrue(textNodes("Kept on this device. Save to server is a separate action.").isEmpty())
        waitText("Server status: content unavailable", true); screenshot("drafts-denied-cleanup")
        assertTrue(textNodes("Remove local draft").isEmpty())
        val before = main { f.transport.calls.size }; click("Review server discard"); click("Confirm server discard")
        await { main { f.drafts.states.value.let { state -> state.localDrafts.isEmpty() && state.serverAcknowledged &&
            state.screen == PostDraftScreen.LOCAL_LIST && state.selected == null && state.discardConfirmation == null } } }
        await { textNodes("Discard this server draft?").isEmpty() }
        waitText("Private on this device", scroll = true)
        main { assertEquals(listOf("deletePostDraft"), f.transport.calls.drop(before).map { it.operationId }); assertEquals("\"1\"", f.transport.calls.last().ifMatch) }
    }

    @Test fun invalidCaptionAndDescriptionPastesExplainRejectionAndValidEditsClearWithoutSending() = withHost { f ->
        val captionLabel = "What did you make?"
        val altLabel = "Image description (optional)"
        val error = "Edit not applied. Check your text: captions and image descriptions each support up to 500 Unicode characters. Your previous text is unchanged."
        // A supplementary character counts as one Unicode scalar, not two UTF-16 units.
        // Accept the exact boundary through the native callback before rejecting 501.
        f.newDraft("🌱".repeat(500))
        val client = main { f.drafts.states.value.selected!!.clientDraftId }
        val ids = main { f.allocatedIds.toList() }
        top(); setText(captionLabel, "Synthetic valid text stays")
        setText(altLabel, "Synthetic retained description")
        await { main { f.drafts.states.value.selected?.let {
            it.caption == "Synthetic valid text stays" && it.altText == "Synthetic retained description" && it.localAcknowledged
        } == true } }
        val before = main { f.drafts.states.value.selected!! }
        top(); setText(captionLabel, "🌱".repeat(501))
        await { main { f.drafts.states.value.issue == PostDraftIssue.INVALID_INPUT } }
        top(); waitText(error, scroll = true)
        val retainedCaption = editor(captionLabel)
        assertEquals(before.caption, retainedCaption.text.toString()); assertTrue(retainedCaption.isVisibleToUser)
        assertTrue(retainedCaption.isContentInvalid); assertEquals(error, retainedCaption.error?.toString())
        main {
            val selected = f.drafts.states.value.selected!!
            assertEquals(client, selected.clientDraftId); assertEquals(before.localRevision, selected.localRevision)
            assertEquals(before.caption, selected.caption); assertEquals(before.altText, selected.altText)
            assertTrue(selected.localAcknowledged); assertNull(f.drafts.states.value.pending)
            assertEquals(ids, f.allocatedIds); assertTrue(f.transport.calls.isEmpty()); assertEquals(0, f.completedTransportCalls)
        }
        screenshot("drafts-invalid-text")
        setText(captionLabel, "Synthetic corrected caption")
        await { main { f.drafts.states.value.let { it.issue == PostDraftIssue.NONE &&
            it.selected?.let { local -> local.caption == "Synthetic corrected caption" && local.localAcknowledged } == true } } }
        // Controller delivery is not a Compose/accessibility publication barrier. Observe the
        // rendered correction too, with fresh nodes and the same bounded transition wait.
        await { textNodes(error).isEmpty() }; waitText(captionLabel, scroll = true)
        await { correctedEditorIsRendered(captionLabel, "Synthetic corrected caption", error) }
        assertTrue(textNodes(error).isEmpty()); assertFalse(editor(captionLabel).isContentInvalid)
        val beforeAlt = main { f.drafts.states.value.selected!! }
        setText(altLabel, "🌱".repeat(501))
        await { main { f.drafts.states.value.issue == PostDraftIssue.INVALID_INPUT } }
        top(); waitText(error, scroll = true)
        val retainedAlt = editor(altLabel)
        assertEquals(beforeAlt.altText, retainedAlt.text.toString()); assertTrue(retainedAlt.isVisibleToUser)
        assertTrue(retainedAlt.isContentInvalid); assertEquals(error, retainedAlt.error?.toString())
        main {
            val selected = f.drafts.states.value.selected!!
            assertEquals(client, selected.clientDraftId); assertEquals(beforeAlt.localRevision, selected.localRevision)
            assertEquals(beforeAlt.caption, selected.caption); assertEquals(beforeAlt.altText, selected.altText)
            assertTrue(selected.localAcknowledged); assertEquals(ids, f.allocatedIds); assertTrue(f.transport.calls.isEmpty())
        }
        setText(altLabel, "Synthetic corrected description")
        await { main { f.drafts.states.value.let { it.issue == PostDraftIssue.NONE &&
            it.selected?.let { local -> local.altText == "Synthetic corrected description" && local.localAcknowledged } == true } } }
        await { textNodes(error).isEmpty() }; waitText(altLabel, scroll = true)
        await { correctedEditorIsRendered(altLabel, "Synthetic corrected description", error) }
        assertTrue(textNodes(error).isEmpty()); assertFalse(editor(altLabel).isContentInvalid)
        main {
            assertEquals(client, f.drafts.states.value.selected!!.clientDraftId)
            assertEquals("Synthetic corrected caption", f.drafts.states.value.selected!!.caption)
            assertEquals(ids, f.allocatedIds); assertTrue(f.transport.calls.isEmpty()); assertEquals(0, f.completedTransportCalls)
            assertNull(f.drafts.states.value.pending); assertFalse(f.drafts.states.value.serverAcknowledged)
        }
    }

    @Test fun lostDiscardResponseUsesDiscardCopyAndOnlyOriginalRetryConfirmsRemoval() = withHost { f ->
        f.newDraft("Synthetic uncertain discard"); f.save()
        main { f.transport.unknownNextMutation = true }
        click("Review server discard"); click("Confirm server discard")
        await { main { f.drafts.states.value.let { state ->
            f.completedTransportCalls == 2 && f.transport.calls.size == 2 && !state.busy && !state.serverAcknowledged &&
                state.pending?.let { pending -> pending.operationId == "deletePostDraft" && pending.phase == "AWAITING_CONFIRMATION" &&
                    pending.attempts == 1 && pending.issue == "OUTCOME_UNKNOWN" && pending.canRetry && !pending.canDiscardUnsent } == true
        } } }
        await { textNodes("Discard this server draft?").isEmpty() }
        val original = main { f.transport.calls.last() }
        top(); waitText("Server discard is not confirmed")
        fullyVisibleWithoutScrolling("Retry original action")
        assertTrue(textNodes("A quick heads-up").isEmpty())
        assertTrue(textNodes("deletePostDraft").isEmpty()); assertTrue(textNodes("AWAITING_CONFIRMATION").isEmpty())
        assertTrue(textNodes("Saved privately on the server.").isEmpty()); assertTrue(textNodes("Cancel unsent server action").isEmpty())
        screenshot("drafts-discard-uncertain")
        f.recreate(); top(); waitText("Server discard is not confirmed")
        fullyVisibleWithoutScrolling("Retry original action")
        main { assertEquals(2, f.transport.calls.size); assertEquals(1, f.drafts.states.value.localDrafts.size) }
        f.allowOriginalRetry(); click("Retry original action")
        await { main { f.drafts.states.value.let { state -> state.pending == null && state.serverAcknowledged &&
            state.localDrafts.isEmpty() && state.selected == null && state.screen == PostDraftScreen.LOCAL_LIST } } }
        main {
            val retry = f.transport.calls.last(); assertEquals(3, f.transport.calls.size)
            assertEquals("deletePostDraft", retry.operationId); assertEquals(original.operationId, retry.operationId)
            assertEquals(original.pathParameters, retry.pathParameters); assertEquals(original.ifMatch, retry.ifMatch)
            assertEquals(original.idempotencyKey!!.use { it }, retry.idempotencyKey!!.use { it })
            assertNull(original.body); assertNull(retry.body)
        }
        waitText("Private on this device", scroll = true)
    }

    private fun value(result: PortResult<*>) = assertTrue("Actual acknowledgement required: $result", result is PortResult.Value)
    private fun await(test: () -> Boolean) {
        repeat(80) { instrumentation.waitForIdleSync(); if (test()) return; SystemClock.sleep(100) }
        failureScreenshot("transition"); fail("Timed out waiting for the explicit synthetic draft host transition")
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val root = ui.rootInActiveWindow ?: return emptyList(); val found = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) { found += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::walk) }
        walk(root); return found
    }
    private fun textNodes(text: String) = nodes().filter {
        fun normalized(value: CharSequence?) = value?.toString()?.replace(Regex("\\s+"), " ")
        normalized(it.text)?.contains(text) == true || normalized(it.contentDescription)?.contains(text) == true || normalized(it.hintText)?.contains(text) == true
    }
    /** Read-only layout assertion against fresh actual editable/clickable accessibility owners.
     * No scroll or click is performed to make the assertion pass. */
    private fun fullyVisibleWithoutScrolling(label: String, editable: Boolean = false) {
        await {
            val root = ui.rootInActiveWindow ?: return@await false
            val viewport = Rect().also(root::getBoundsInScreen)
            textNodes(label).any { start ->
                var owner: AccessibilityNodeInfo? = start
                while (owner != null && !(if (editable) owner.isEditable else owner.isClickable)) owner = owner.parent
                owner?.let { node ->
                    val bounds = Rect().also(node::getBoundsInScreen)
                    node.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0 && viewport.contains(bounds)
                } == true
            }
        }
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
    private fun top() { repeat(20) { if (textNodes("← Back").any { it.isVisibleToUser }) return; scroll(false) }; waitText("← Back") }
    private fun waitText(text: String, scroll: Boolean = false) {
        repeat(35) { attempt ->
            instrumentation.waitForIdleSync(); if (textNodes(text).any { it.isVisibleToUser }) return
            if (scroll && attempt >= 4) scroll(true)
            SystemClock.sleep(100)
        }
        failureScreenshot(text); fail("Expected visible synthetic draft control: $text")
    }
    private fun clickable(text: String): AccessibilityNodeInfo {
        waitText(text, true); var node = textNodes(text).first { it.isVisibleToUser }
        while (!node.isClickable) node = checkNotNull(node.parent)
        assertTrue("Action enabled: $text", node.isEnabled); return node
    }
    private fun click(text: String) { assertTrue(clickable(text).performAction(AccessibilityNodeInfo.ACTION_CLICK)); instrumentation.waitForIdleSync() }
    private fun editor(label: String): AccessibilityNodeInfo {
        waitText(label, true)
        return textNodes(label).asSequence().mapNotNull { start ->
            var current: AccessibilityNodeInfo? = start
            while (current != null && !current.isEditable) current = current.parent
            current?.takeIf { it.isVisibleToUser }
        }.firstOrNull() ?: error("Synthetic draft label has no editable accessibility owner")
    }
    /** Fresh, non-throwing rendered observation; never caches a node across a transition. */
    private fun correctedEditorIsRendered(label: String, expectedText: String, obsoleteError: String): Boolean {
        if (textNodes(obsoleteError).isNotEmpty()) return false
        val field = textNodes(label).asSequence().mapNotNull { start ->
            var current: AccessibilityNodeInfo? = start
            while (current != null && !current.isEditable) current = current.parent
            current?.takeIf { it.isVisibleToUser }
        }.firstOrNull() ?: return false
        return field.text?.toString() == expectedText && !field.isContentInvalid && field.error.isNullOrEmpty()
    }
    private fun setText(label: String, value: String, settle: Boolean = true) {
        val editor = editor(label)
        assertTrue(editor.isEnabled)
        assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        if (settle) instrumentation.waitForIdleSync()
    }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); instrumentation.waitForIdleSync() }
    private fun failureScreenshot(reason: String) {
        val method = checkNotNull(testName.methodName)
        val identity = method.hashCode().toUInt().toString(16)
        val label = reason.lowercase().filter(Char::isLetterOrDigit).take(18)
        // Different tests and repeated failures retain different original captures. The
        // fixed success filenames remain the verifier's exact source-bound inventory.
        screenshot("failure-${method.take(28).lowercase()}-$identity-${++failureCaptureSequence}-$label")
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(650); instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(ui.takeScreenshot())
        val dir = File(instrumentation.targetContext.noBackupFilesDir, "post-draft-host-ui-evidence").also { check(it.mkdirs() || it.isDirectory) }
        File(dir, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    }
    companion object { private var retainedFixture: Any? = null }
}
