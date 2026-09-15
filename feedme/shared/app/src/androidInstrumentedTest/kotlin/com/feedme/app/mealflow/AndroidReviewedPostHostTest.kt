package com.feedme.app.mealflow

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
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

/** Separate native acceptance candidate: actual Android encrypted session, actual configured
 * controllers/owners/queue and shared Compose review UI. Principal/policy/service are SYNTHETIC.
 * No HTTP/PG, login/provider, media upload, circles, process death, iOS or full-feature claim.
 * Existing legacy/progress 49 tests and transports are intentionally not modified. */
@RunWith(AndroidJUnit4::class)
class AndroidReviewedPostHostTest {
    @get:Rule val testName = TestName()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui get() = instrumentation.uiAutomation
    private var failureSequence = 0
    private var clickSequence = 0
    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main.immediate) { block() } }
    private inner class Fixture {
        val session = NativeMealFlowTestSession(instrumentation.targetContext)
        var time = session.clock.nowMillis()
        var online = true
        val ids = mutableListOf<String>()
        val transport = NativeReviewedPostTestTransport(session) { time }
        var meals: MealRequestController? = null
        var kitchen: MealKitchenControllers? = null
        lateinit var access: AuthenticatedMealPlanningAccess
        lateinit var identity: NativeReviewedPostTestIdentity
        lateinit var integration: NativeReviewedPostTestIntegration
        lateinit var scenario: ActivityScenario<NativeReviewedPostTestActivity>
        val entry get() = checkNotNull(kitchen?.reviewedPosts)
        val drafts get() = entry.drafts
        val publications get() = entry.publications
        suspend fun configure() {
            access = AuthenticatedMealPlanningAccess.fromSession(checkNotNull(session.currentAccess()), transport)
            identity = NativeReviewedPostTestIdentity(access, session)
            integration = NativeReviewedPostTestIntegration(identity)
            val clock = EpochClock { time }
            val connectivity = ConnectivityPort { if (online) Connectivity.ONLINE else Connectivity.OFFLINE }
            val operations = MealOperationIds { UUID.randomUUID().toString().also { ids += it } }
            val actualMeals = MealRequestController(access, session.boundary, session.dispatcher, clock, connectivity,
                operations, MealFlowPolicy(86_400_000, 86_400_000, 5, 100)).also { meals = it }
            kitchen = MealKitchenControllers.createWithReviewedPosts(access, session.boundary, session.dispatcher,
                clock, connectivity, operations, actualMeals, actualMeals.draftReadiness,
                CookingFlowPolicy(300_000, 65_536, 65_536), CookbookPolicy(20, 60_000),
                PostDraftClientPolicy(8, 1_048_576, 262_144, 128, 64, 20, 100, 60_000),
                // The synthetic replies are explicitly bounded below the journal's escaped
                // reply reservation. A 262,144-byte cap cannot fit a 1 MiB journal at 6x.
                PostPublicationClientPolicy(1_048_576, 65_536, NativeReviewedPostTestTransport.MAX_RESPONSE_BYTES,
                    64, 128, 64, 10, 10, 32, 65_536, 4096, 60_000),
                integration)
            NativeReviewedPostHostOwner.current = NativeReviewedPostHostOwner(entry)
            // Explicit test-fixture read before attach, no writes/send/migration/hidden tokens.
            value(drafts.restoreLocal())
            check(transport.calls.isEmpty() && ids.isEmpty())
        }
        fun settled() = await { main { !drafts.states.value.busy } }
        fun newDraft(caption: String) {
            click("Start a fresh draft"); setText("What did you make?", caption)
            await { main { drafts.states.value.selected?.let { it.caption == caption && it.localAcknowledged } == true } }
        }
        fun saveInitialText() {
            click("Save privately to server")
            await { main { drafts.states.value.serverAcknowledged && drafts.states.value.pending == null && !drafts.states.value.busy } }
        }
        fun choices(privateSave: Boolean = false) {
            top(); click("Review audience & recipe saves")
            // A clean explicitly configured owner creates CURRENT_TEXT, not an implicit
            // legacy upgrade. All choices below use the shared screen and actual entry.
            waitText("Audience & recipe saves", true)
            click("Only me")
            click("Do not keep on My Plate")
            click("Do not allow recipe saves")
            click(if (privateSave) "A private Save" else "A publication")
            click("Load current disclosure")
            waitText("Synthetic test disclosure", true)
            click("Keep these choices on device")
            await { main { drafts.states.value.selected?.let { it.requiresReviewedSave && it.localAcknowledged } == true } }
            waitText("What did you make?", true)
        }
        fun reviewPublication(saved: Boolean = false) {
            top(); click("Review publication")
            click(if (saved) "Review this saved-draft publication" else "Review direct publication")
            await { main { publications.states.value.review != null } }
        }
        fun allowOriginalRetry() = main {
            time = maxOf(time + 60_000, publications.states.value.pending?.earliestRetryAtMillis ?: time,
                drafts.states.value.earliestRetryAtMillis ?: time)
        }
        fun recreate() { scenario.recreate(); instrumentation.waitForIdleSync(); settled() }
        suspend fun closeControllers() {
            kitchen?.let { value(it.close()) }
            meals?.let { value(it.close()) }
        }
        fun releaseGates() {
            transport.publicationGate?.complete(Unit); transport.privateMutationGate?.complete(Unit)
            transport.readGate?.complete(Unit)
            if (::integration.isInitialized) {
                integration.disclosureGate?.complete(Unit); integration.publicationCheckGate?.complete(Unit)
            }
        }
    }
    private fun withHost(block: (Fixture) -> Unit) {
        val f = Fixture(); var opened = false; var launched = false; var failure: Throwable? = null
        try {
            main {
                check(retainedFixture == null && NativeReviewedPostHostOwner.current == null)
                retainedFixture = f // BEFORE native setup; failed cleanup preserves actual ownership.
                value(f.session.open()); opened = true; f.configure()
            }
            f.scenario = ActivityScenario.launch(Intent(instrumentation.targetContext, NativeReviewedPostTestActivity::class.java))
            launched = true; waitText("Start a fresh draft", true); block(f)
        } catch (t: Throwable) {
            failure = t
            if (launched) try { failureScreenshot("assertion") } catch (capture: Throwable) { t.addSuppressed(capture) }
            throw t
        } finally {
            try {
                main { f.releaseGates() }
                if (launched) f.scenario.close()
                main {
                    f.closeControllers()
                    value(if (opened) f.session.retireAndClose() else f.session.close())
                    NativeReviewedPostHostOwner.current = null; retainedFixture = null
                }
            } catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    @Test fun directLocalReviewBackAndExplicitPublishNeverCreateAPrivateServerDraft() = withHost { f ->
        f.newDraft("SYNTHETIC_DIRECT_REVIEW"); f.choices()
        val idsBefore = main { f.ids.toList() }
        f.reviewPublication()
        val prepared = main { checkNotNull(f.publications.states.value.review) }
        main {
            assertEquals(idsBefore, f.ids); assertTrue(f.transport.calls.isEmpty())
            val request = json(prepared.snapshot.exactProposedPostWrite)
            assertFalse("draftId" in request); assertFalse("draftVersion" in request)
            assertEquals("SYNTHETIC_DIRECT_REVIEW", request.getValue("caption").jsonPrimitive.content)
            assertFalse(f.publications.states.value.acknowledged)
        }
        waitText("Publish this post", true); screenshot("reviewed-direct-review")
        back(); waitText("What did you make?", true)
        main { assertNull(f.publications.states.value.review); assertTrue(f.transport.calls.isEmpty()); assertEquals(idsBefore, f.ids) }
        f.reviewPublication()
        val actualReview = main { checkNotNull(f.publications.states.value.review) }
        click("Publish this post")
        await { main { f.publications.states.value.let { it.acknowledged && it.phase == PostComposerPhase.PUBLISHED } } }
        main {
            val call = f.transport.calls.single()
            assertEquals("publishPost", call.operationId); assertNull(call.ifMatch); assertTrue(call.pathParameters.isEmpty())
            assertArrayEquals(actualReview.snapshot.exactProposedPostWrite.encodeUtf8(), call.body!!.copyForCodec())
            assertEquals(idsBefore.size + 1, f.ids.size); assertEquals(1, f.transport.publishedCount)
            assertEquals("\"1\"", f.publications.states.value.etag)
            assertArrayEquals(f.transport.returnedReplies.single().body!!.copyForCodec(),
                f.publications.states.value.exactCanonicalPost!!.encodeUtf8())
        }
        waitText("Action confirmed", true); screenshot("reviewed-direct-published")
    }

    @Test fun explicitPrivateCreateThenReviewedPatchThenSavedPublicationUseSeparateExactConfirmations() = withHost { f ->
        f.newDraft("SYNTHETIC_INITIAL_PRIVATE"); f.saveInitialText()
        main { assertEquals(listOf("createPostDraft"), f.transport.calls.map { it.operationId }); assertEquals(0, f.transport.publishedCount) }
        top(); setText("What did you make?", "SYNTHETIC_REVIEWED_PRIVATE")
        await { main { f.drafts.states.value.selected?.caption == "SYNTHETIC_REVIEWED_PRIVATE" && f.drafts.states.value.selected!!.localAcknowledged } }
        f.choices(privateSave = true); val idsBeforeReview = main { f.ids.toList() }
        top(); click("Review private Save")
        await { main { f.drafts.states.value.reviewedSave != null } }
        val first = main { checkNotNull(f.drafts.states.value.reviewedSave) }
        waitText("Confirm private Save", true); screenshot("reviewed-private-patch-review")
        main {
            assertEquals(idsBeforeReview, f.ids); assertEquals(1, f.transport.calls.size); assertEquals(0, f.transport.publishedCount)
            assertEquals("\"1\"", first.snapshot.target.etag)
        }
        back(); await { main { f.drafts.states.value.reviewedSave == null } }
        main { assertEquals(1, f.transport.calls.size); assertEquals(idsBeforeReview, f.ids) }
        waitText("Private on this device", true); click("SYNTHETIC_REVIEWED_PRIVATE")
        top(); click("Review private Save"); await { main { f.drafts.states.value.reviewedSave != null } }
        val confirmed = main { checkNotNull(f.drafts.states.value.reviewedSave) }
        click("Confirm private Save")
        await { main { f.drafts.states.value.let { it.serverAcknowledged && it.pending == null && !it.busy } } }
        main {
            val patch = f.transport.calls.last(); assertEquals("updatePostDraft", patch.operationId)
            assertArrayEquals(confirmed.snapshot.exactProposedPatch.encodeUtf8(), patch.body!!.copyForCodec())
            assertEquals(confirmed.snapshot.target.etag, patch.ifMatch)
            assertEquals(confirmed.snapshot.target.draftId, patch.pathParameters.getValue("draftId"))
            assertEquals(0, f.transport.publishedCount)
        }
        screenshot("reviewed-private-patch-confirmed")
        // Natural Save→Review: no dummy edit, reopen or marker cleanup is inserted.
        f.reviewPublication(saved = true)
        val publication = main { checkNotNull(f.publications.states.value.review) }
        main {
            assertEquals(2, f.transport.calls.size)
            val body = json(publication.snapshot.exactProposedPostWrite)
            assertEquals(confirmed.snapshot.target.draftId, body.getValue("draftId").jsonPrimitive.content)
            assertEquals("2", body.getValue("draftVersion").jsonPrimitive.content)
            assertEquals("SYNTHETIC_REVIEWED_PRIVATE", body.getValue("caption").jsonPrimitive.content)
        }
        waitText("Publish this post", true); screenshot("reviewed-saved-publication-review")
        click("Publish this post")
        await { main { f.publications.states.value.acknowledged && f.publications.states.value.phase == PostComposerPhase.PUBLISHED } }
        main {
            assertEquals(listOf("createPostDraft", "updatePostDraft", "publishPost"), f.transport.calls.map { it.operationId })
            assertArrayEquals(publication.snapshot.exactProposedPostWrite.encodeUtf8(), f.transport.calls.last().body!!.copyForCodec())
            assertEquals(1, f.transport.publishedCount); assertTrue(f.integration.calls.contains("new-publication:SAVED_DRAFT"))
        }
        waitText("Action confirmed", true); screenshot("reviewed-saved-published")
    }

    @Test fun lostPublicationThenNewerNativeEditReplaysOnlyOriginalAcrossActivityRecreation() = withHost { f ->
        f.newDraft("SYNTHETIC_ORIGINAL_TO_PUBLISH"); f.choices()
        f.reviewPublication()
        main { f.transport.unknownNextPublication = true }; click("Publish this post")
        await { main { f.transport.completedCalls.size == 1 && f.publications.states.value.pending?.observedAttempts == 1 &&
            !f.publications.states.value.acknowledged } }
        val original = main { f.transport.calls.single() }
        val idsAfter = main { f.ids.toList() }
        back(); waitText("What did you make?", true)
        setText("What did you make?", "SYNTHETIC_NEWER_RETAINED_ONLY")
        await { main { f.drafts.states.value.selected?.let { it.caption == "SYNTHETIC_NEWER_RETAINED_ONLY" && it.localAcknowledged } == true } }
        f.recreate()
        main {
            assertEquals(1, f.transport.calls.size); assertEquals(idsAfter, f.ids); assertEquals(1, f.transport.publishedCount)
            assertFalse(f.publications.states.value.acknowledged)
            assertEquals("SYNTHETIC_NEWER_RETAINED_ONLY", f.drafts.states.value.selected!!.caption)
        }
        top(); click("Review retained publication"); click("Review original publication")
        await { main { f.publications.states.value.retry != null } }
        val retryReview = main { checkNotNull(f.publications.states.value.retry) }
        main {
            assertArrayEquals(original.body!!.copyForCodec(), retryReview.original.exactOriginalPostWrite.encodeUtf8())
            assertEquals(original.idempotencyKey!!.use { it }, retryReview.original.commandId)
            assertTrue(retryReview.separatelyObservedCurrentLocal.exactHistoricalSnapshot.encodeUtf8().decodeToString()
                .contains("SYNTHETIC_NEWER_RETAINED_ONLY"))
            assertEquals(1, retryReview.observedAttempts); assertEquals(1, f.transport.calls.size)
        }
        waitText("Current local changes", true); screenshot("reviewed-original-versus-newer")
        f.allowOriginalRetry()
        // Time changed AFTER prepare: replace it with a fresh explicit review; never extend a token.
        back(); top(); click("Review retained publication"); click("Review original publication")
        click("Retry this original publication")
        await { main { f.publications.states.value.acknowledged && f.publications.states.value.phase == PostComposerPhase.PUBLISHED } }
        main {
            assertEquals(2, f.transport.calls.size); sameOriginal(original, f.transport.calls.last())
            assertEquals(idsAfter, f.ids); assertEquals(1, f.transport.publishedCount)
            assertEquals("SYNTHETIC_ORIGINAL_TO_PUBLISH", json(f.publications.states.value.exactCanonicalPost!!).getValue("caption").jsonPrimitive.content)
            // Actual retained owner read, not a seeded remainder/ACK flag.
            value(f.drafts.restoreLocal())
            val remainder = f.drafts.states.value.unsubmittedRemainders.single()
            assertFalse(remainder.editable); assertTrue(remainder.newerLocalRevision > remainder.reviewedLocalRevision)
            assertTrue(f.drafts.states.value.localDrafts.isEmpty())
        }
        screenshot("reviewed-original-published-newer-retained")
    }

    @Test fun zeroAttemptOfflineOriginalRequiresExplicitReviewedCancellationAndKeepsLocalDraft() = withHost { f ->
        f.newDraft("SYNTHETIC_CANCEL_UNSENT"); f.choices()
        f.reviewPublication()
        main { f.online = false }; click("Publish this post")
        // Actual admission fails before transport. Restore is an explicit historical read, not ACK.
        click("Refresh retained publication history")
        await { main { f.publications.states.value.pending?.let { it.observedAttempts == 0 && it.phase == "AWAITING_CONFIRMATION" } == true } }
        main { assertTrue(f.transport.calls.isEmpty()); assertFalse(f.publications.states.value.acknowledged) }
        click("Review cancellation"); await { main { f.publications.states.value.unsentCancellation != null } }
        waitText("Cancel this unsent publication", true); screenshot("reviewed-unsent-cancellation")
        back()
        main { assertTrue(f.transport.calls.isEmpty()); assertFalse(f.publications.states.value.acknowledged) }
        top(); click("Review retained publication"); click("Review cancellation"); click("Cancel this unsent publication")
        await { main { f.publications.states.value.acknowledged && f.publications.states.value.phase == PostComposerPhase.CANCELLED_UNSENT } }
        main {
            assertTrue(f.transport.calls.isEmpty()); assertEquals(0, f.transport.publishedCount)
            assertNull(f.publications.states.value.exactCanonicalPost); assertNull(f.publications.states.value.etag)
            value(f.drafts.restoreLocal())
            assertEquals("SYNTHETIC_CANCEL_UNSENT", f.drafts.states.value.localDrafts.single().caption)
            assertNull(f.drafts.states.value.publicationHold)
        }
        screenshot("reviewed-unsent-cancelled-local-retained")
    }

    @Test fun backDuringNonCooperativePublicationFencesLateAcknowledgementWithoutResending() = withHost { f ->
        f.newDraft("SYNTHETIC_LATE_PUBLICATION"); f.choices()
        f.reviewPublication()
        val gate = CompletableDeferred<Unit>(); main { f.transport.publicationGate = gate }
        click("Publish this post"); await { main { f.transport.calls.size == 1 } }
        back()
        main { assertFalse(f.publications.states.value.acknowledged); assertTrue(f.transport.completedCalls.isEmpty()) }
        main { f.transport.publicationGate = null; gate.complete(Unit) }
        await { main { f.transport.completedCalls.size == 1 } }
        waitText("What did you make?", true)
        main {
            assertFalse(f.publications.states.value.acknowledged)
            assertEquals(1, f.transport.calls.size); assertEquals(1, f.transport.publishedCount)
            assertEquals("SYNTHETIC_LATE_PUBLICATION", f.drafts.states.value.selected!!.caption)
        }
        f.recreate()
        main { assertEquals(1, f.transport.calls.size); assertFalse(f.publications.states.value.acknowledged) }
        screenshot("reviewed-back-late-result-unconfirmed")
    }

    @Test fun realNativeSessionInvalidationRedactsPendingPublicationAndRejectsLateResult() = withHost { f ->
        val marker = "SYNTHETIC_NATIVE_PRIVATE_REVIEW"
        f.newDraft(marker); f.choices()
        f.reviewPublication()
        val gate = CompletableDeferred<Unit>(); main { f.transport.publicationGate = gate }
        click("Publish this post"); await { main { f.transport.calls.size == 1 } }
        main { f.identity.revokeBeforeSessionMutation(); value(f.session.invalidate()) }
        await { main { f.publications.states.value.phase == PostComposerPhase.UNAVAILABLE &&
            f.drafts.states.value.phase == PostDraftPhase.UNAVAILABLE } }
        await { textNodes(marker).isEmpty() }
        main { f.transport.publicationGate = null; gate.complete(Unit) }
        await { main { f.transport.completedCalls.size == 1 } }
        main {
            assertNull(f.publications.states.value.review); assertNull(f.publications.states.value.retry)
            assertNull(f.publications.states.value.exactCanonicalPost); assertFalse(f.publications.states.value.acknowledged)
            assertTrue(f.publications.states.value.history.isEmpty()); assertNull(f.drafts.states.value.selected)
            assertTrue(f.drafts.states.value.localDrafts.isEmpty()); assertEquals(1, f.transport.calls.size)
        }
        await { textNodes(marker).isEmpty() }; screenshot("reviewed-native-session-redacted")
    }

    private fun sameOriginal(expected: ApiCall, actual: ApiCall) {
        assertEquals(expected.operationId, actual.operationId); assertEquals(expected.pathParameters, actual.pathParameters)
        assertEquals(expected.queryParameters, actual.queryParameters); assertEquals(expected.ifMatch, actual.ifMatch)
        assertEquals(expected.idempotencyKey!!.use { it }, actual.idempotencyKey!!.use { it })
        assertArrayEquals(expected.body!!.copyForCodec(), actual.body!!.copyForCodec())
    }
    private fun json(value: WireDocument) = Json.parseToJsonElement(value.encodeUtf8().decodeToString()).jsonObject
    private fun <T> value(result: PortResult<T>): T = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> throw AssertionError("Actual native acknowledgement required: " + result.reason)
    }
    private fun await(test: () -> Boolean) {
        repeat(100) { instrumentation.waitForIdleSync(); if (test()) return; SystemClock.sleep(100) }
        failureScreenshot("transition"); fail("Timed out waiting for actual reviewed native transition")
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        // rootInActiveWindow/getChild may reuse cached virtual nodes. Quiet events alone
        // are not cache invalidation. Preserve the old path below API 34, unverified here.
        if (Build.VERSION.SDK_INT >= 34) assertTrue("Accessibility cache cleared", ui.clearCache())
        val root = ui.rootInActiveWindow ?: return emptyList(); val found = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) { found += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::walk) }
        walk(root); return found
    }
    private fun textNodes(text: String, tree: List<AccessibilityNodeInfo> = nodes()) = tree.filter {
        fun normal(value: CharSequence?) = value?.toString()?.replace(Regex("\\s+"), " ")
        normal(it.text)?.contains(text) == true || normal(it.contentDescription)?.contains(text) == true || normal(it.hintText)?.contains(text) == true
    }
    private fun scroll(forward: Boolean) {
        val bounds = Rect().also { ui.rootInActiveWindow?.getBoundsInScreen(it) }; check(!bounds.isEmpty)
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
        repeat(40) { attempt ->
            instrumentation.waitForIdleSync(); if (textNodes(text).any { it.isVisibleToUser }) return
            if (scroll && attempt >= 4) scroll(true)
            SystemClock.sleep(100)
        }
        failureScreenshot("text"); fail("Expected visible reviewed control: $text")
    }
    /** Same accepted fresh-tree/two-stable-enabled-bounds driver as cooking/meal host.
     * Once one ACTION_CLICK is submitted, a false result fails; it never retries submission. */
    private fun click(text: String) {
        val invocation = ++clickSequence
        val observations = linkedSetOf<String>()
        var previousTarget: Triple<Int, String?, Rect>? = null
        try {
            repeat(40) { attempt ->
                instrumentation.waitForIdleSync()
                val tree = nodes()
                val labels = textNodes(text, tree).filter { it.isVisibleToUser }
                // A review heading may repeat its confirm button's text. Resolve clickable
                // owners from this SAME fresh tree rather than choosing a static title.
                val candidates = labels.asSequence().mapNotNull { label ->
                    var node: AccessibilityNodeInfo? = label
                    while (node != null && !node.isClickable) node = node.parent
                    node?.takeIf { it.isVisibleToUser && it.isEnabled }
                }.distinct().toList()
                // Duplicate static headings do not name actions. Duplicate semantic labels
                // for the same owner collapse; distinct enabled actions are never guessed.
                check(candidates.size <= 1) { "Ambiguous reviewed action: $text; invocation=$invocation" }
                val action = candidates.singleOrNull()
                observations += "invocation=$invocation, attempt=$attempt, nodes=${tree.size}, labels=${labels.size}, " +
                    "windows=${tree.map { it.windowId }.distinct()}, candidates=${candidates.size}; " +
                    tree.filter { it.isVisibleToUser }.take(8).joinToString(" | ") {
                        "${it.text ?: it.contentDescription ?: it.hintText} [${Rect().also(it::getBoundsInScreen)}]"
                    }
                if (action != null) {
                    val bounds = Rect().also { action.getBoundsInScreen(it) }
                    observations += "visible=" + action.isVisibleToUser + ", enabled=" + action.isEnabled + ", bounds=" + bounds
                    if (action.isVisibleToUser && action.isClickable && action.isEnabled && !bounds.isEmpty) {
                        val target = Triple(action.windowId, action.className?.toString(), bounds)
                        if (target == previousTarget) {
                            assertTrue("Enabled action required: " + text, action.isEnabled)
                            assertTrue("Native ACTION_CLICK accepted: " + text, action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                            instrumentation.waitForIdleSync(); return
                        }
                        previousTarget = target
                    } else previousTarget = null
                } else {
                    observations += if (labels.isEmpty()) "No visible matching label" else "Visible labels without clickable ancestor"
                    previousTarget = null
                    if (attempt >= 4) scroll(true)
                }
                SystemClock.sleep(100)
            }
            fail("No stable visible enabled action: " + text + "; " + observations.joinToString(" | "))
        } catch (failure: Throwable) {
            try { failureScreenshot("click-$invocation") } catch (capture: Throwable) { failure.addSuppressed(capture) }
            throw failure
        }
    }
    private fun setText(label: String, value: String) {
        waitText(label, true)
        val editor = textNodes(label).asSequence().mapNotNull { start ->
            var current: AccessibilityNodeInfo? = start
            while (current != null && !current.isEditable) current = current.parent
            current?.takeIf { it.isVisibleToUser }
        }.firstOrNull() ?: error("No actual editable reviewed accessibility owner")
        assertTrue(editor.isEnabled)
        assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        instrumentation.waitForIdleSync()
    }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); instrumentation.waitForIdleSync() }
    private fun failureScreenshot(reason: String) {
        val method = checkNotNull(testName.methodName)
        screenshot("failure-" + method.take(28).lowercase() + "-" + method.hashCode().toUInt().toString(16) +
            "-" + (++failureSequence) + "-" + reason)
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(650); instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(ui.takeScreenshot())
        val directory = File(instrumentation.targetContext.noBackupFilesDir, "reviewed-post-host-ui-evidence")
            .also { check(it.mkdirs() || it.isDirectory) }
        File(directory, name + ".png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    companion object { private var retainedFixture: Any? = null }
}
