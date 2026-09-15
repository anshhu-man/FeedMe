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

/**
 * Bounded acceptance of the shared restored-local review/confirm screen on a REAL Android
 * encrypted store and a genuinely reopened runtime/store generation. Setup uses explicit
 * public controller/facade actions, never seeded journals, invented ACKs or dummy edits.
 * Account mapping, policy and remote idempotency ledger remain SYNTHETIC.
 *
 * This is controlled close/reopen in one instrumentation process, NOT process death. It does
 * not accept sign-in/providers, media/recipe editors, arbitrary reply sizes or iOS. Existing
 * six scenarios, host, transport, identity and cleanup stay unchanged.
 */
@RunWith(AndroidJUnit4::class)
class AndroidRestoredPostRetentionHostTest {
    @get:Rule val testName = TestName()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui get() = instrumentation.uiAutomation
    private var failureSequence = 0
    private var clickSequence = 0
    private fun <T> main(block: suspend () -> T): T = runBlocking {
        withContext(Dispatchers.Main.immediate) { block() }
    }

    private inner class Fixture {
        val session = NativeMealFlowTestSession(instrumentation.targetContext)
        var time = session.clock.nowMillis()
        val ids = mutableListOf<String>()
        // This exact synthetic remote ledger survives the local runtime reopen.
        val transport = NativeReviewedPostTestTransport(session) { time }
        var meals: MealRequestController? = null
        var kitchen: MealKitchenControllers? = null
        lateinit var identity: NativeReviewedPostTestIdentity
        lateinit var integration: NativeReviewedPostTestIntegration
        var scenario: ActivityScenario<NativeReviewedPostTestActivity>? = null
        lateinit var original: ApiCall
        lateinit var root: String
        var newerRevision = -1L
        val entry get() = checkNotNull(kitchen?.reviewedPosts)
        val drafts get() = entry.drafts
        val publications get() = entry.publications

        suspend fun configure(pristine: Boolean) {
            val beforeCalls = transport.calls.toList()
            val beforeIds = ids.toList()
            val access = AuthenticatedMealPlanningAccess.fromSession(checkNotNull(session.currentAccess()), transport)
            identity = NativeReviewedPostTestIdentity(access, session)
            integration = NativeReviewedPostTestIntegration(identity)
            val clock = EpochClock { time }
            val connectivity = ConnectivityPort { Connectivity.ONLINE }
            val operations = MealOperationIds { UUID.randomUUID().toString().also { ids += it } }
            val actualMeals = MealRequestController(access, session.boundary, session.dispatcher, clock, connectivity,
                operations, MealFlowPolicy(86_400_000, 86_400_000, 5, 100)).also { meals = it }
            kitchen = MealKitchenControllers.createWithReviewedPosts(access, session.boundary, session.dispatcher,
                clock, connectivity, operations, actualMeals, actualMeals.draftReadiness,
                CookingFlowPolicy(300_000, 65_536, 65_536), CookbookPolicy(20, 60_000),
                PostDraftClientPolicy(8, 1_048_576, 262_144, 128, 64, 20, 100, 60_000),
                PostPublicationClientPolicy(1_048_576, 65_536, NativeReviewedPostTestTransport.MAX_RESPONSE_BYTES,
                    64, 128, 64, 10, 10, 32, 65_536, 4096, 60_000), integration)
            value(entry.restore()) // Explicit fixture history read: no consent/retention.
            assertEquals(beforeCalls, transport.calls); assertEquals(beforeIds, ids)
            if (pristine) { assertTrue(beforeCalls.isEmpty()); assertTrue(beforeIds.isEmpty()) }
            assertFalse(publications.states.value.acknowledged)
            NativeReviewedPostHostOwner.current = NativeReviewedPostHostOwner(entry)
        }

        suspend fun closeControllers() {
            kitchen?.let { value(it.close()); kitchen = null }
            meals?.let { value(it.close()); meals = null }
        }

        /** Actual API setup; no native creation/choice-editor coverage is inferred here. */
        suspend fun prepareLostOriginalAndNewerLocal() {
            val local = value(drafts.newLocalDraft(ORIGINAL, null)).selected!!
            root = local.clientDraftId
            assertTrue(local.localAcknowledged)
            val choices = ReviewedPostChoices(ORIGINAL, OptionalValue.Absent, emptyList(),
                PublicationAudience.OnlyYou, false, OptionalValue.Absent, false,
                integration.disclosureValue, OptionalValue.Absent)
            val chosen = value(entry.editChoices(root, local.localRevision, choices)).selected!!
            assertTrue(chosen.localAcknowledged && chosen.requiresReviewedSave)
            val review = value(entry.prepareSelectedPublication(root, chosen.localRevision, ReviewedPostBranch.DIRECT_LOCAL))
            transport.unknownNextPublication = true
            val unknown = value(publications.confirmPublish(review.token))
            assertEquals(1, unknown.pending!!.observedAttempts); assertFalse(unknown.acknowledged)
            original = transport.calls.single()
            assertEquals("publishPost", original.operationId)
            assertArrayEquals(review.snapshot.exactProposedPostWrite.encodeUtf8(), original.body!!.copyForCodec())
            value(drafts.openLocal(root))
            val newer = value(drafts.editCaption(root, NEWER)).selected!!
            assertTrue(newer.localAcknowledged); newerRevision = newer.localRevision
            assertTrue(newerRevision > chosen.localRevision)
            assertEquals(1, transport.completedCalls.size); assertEquals(1, transport.publishedCount)
        }

        suspend fun reopenAndObtainActualReceiptReady() {
            val before = records()
            val previous = checkNotNull(session.currentAccess())
            val oldRuntime = session.runtime
            val beforeIds = ids.toList()
            identity.revokeBeforeSessionMutation()
            closeControllers()
            NativeReviewedPostHostOwner.current = null
            value(session.reopenRetainedForTest())
            val restored = checkNotNull(session.currentAccess())
            assertNotSame(oldRuntime, session.runtime)
            assertNotSame(previous.lease, restored.lease)
            assertNotSame(previous.store, restored.store)
            assertEquals(previous.originBinding, restored.originBinding)
            assertEquals(previous.scope, restored.scope)
            configure(pristine = false)
            sameRecords(before, records())
            assertEquals(beforeIds, ids)
            assertEquals(1, transport.calls.size)
            time += 120_000
            val review = value(publications.prepareOriginalRetry()).retry!!
            assertEquals(newerRevision, review.separatelyObservedCurrentLocal.localRevision)
            assertArrayEquals(original.body!!.copyForCodec(), review.original.exactOriginalPostWrite.encodeUtf8())
            val reply = publications.retryOriginal(review.token)
            assertTrue(reply is PortResult.Failure)
            assertEquals(FailureReason.CONFLICT, (reply as PortResult.Failure).reason)
            // Actual response returned and queue durably retained it; restored newer edit has
            // no old-lease delivery witness, so domain finalization must still refuse.
            assertEquals(2, transport.completedCalls.size); assertEquals(2, transport.calls.size)
            sameOriginal(original, transport.calls.last())
            assertEquals(1, transport.publishedCount); assertEquals("RECEIPT_READY", commandPhase())
            assertFalse(publications.states.value.acknowledged)
            assertEquals(beforeIds, ids)
            value(drafts.restoreLocal())
            assertFalse(drafts.states.value.localDrafts.single().localAcknowledged)
        }

        suspend fun record(key: RecordKey): PrivateRecord? {
            val current = checkNotNull(session.currentAccess())
            return value(current.store.read(current.scope, key))
        }
        suspend fun records(): Map<RecordKey, PrivateRecord?> {
            val current = checkNotNull(session.currentAccess())
            val command = original.idempotencyKey!!.use { it }
            return listOf(
                RecordKey("mealflow.post-drafts.v1", current.originBinding),
                RecordKey("mealflow.post-publications.v1", current.originBinding),
                RecordKey("feedme.command.metadata", command),
                RecordKey("feedme.command.request", command),
                RecordKey("feedme.command.receipt", command),
                RecordKey("feedme.command.index", "v1"),
            ).associateWith { record(it) }
        }
        suspend fun draftRecord(): PrivateRecord {
            val current = checkNotNull(session.currentAccess())
            return checkNotNull(record(RecordKey("mealflow.post-drafts.v1", current.originBinding)))
        }
        suspend fun commandPhase(): String = Json.parseToJsonElement(checkNotNull(
            record(RecordKey("feedme.command.metadata", original.idempotencyKey!!.use { it }))
        ).payload.copyForCodec().decodeToString()).jsonObject.getValue("phase").jsonPrimitive.content

        fun attach() {
            check(scenario == null)
            scenario = ActivityScenario.launch(Intent(instrumentation.targetContext, NativeReviewedPostTestActivity::class.java))
            waitText("Private on this device", true)
            click(NEWER)
            await { main { drafts.states.value.selected?.let {
                it.clientDraftId == root && it.localRevision == newerRevision && !it.localAcknowledged
            } == true } }
        }
        fun openRecovery() {
            top(); click("Review restored local changes")
            waitText("Your recovered draft", true)
            waitText(NEWER, true)
            waitText("Nothing will be published.", true)
        }
    }

    private fun withRestoredHost(block: (Fixture) -> Unit) {
        val f = Fixture(); var opened = false; var failure: Throwable? = null
        try {
            main {
                check(retainedFixture == null && NativeReviewedPostHostOwner.current == null)
                retainedFixture = f // Retain before opening; cleanup failure preserves ownership.
                value(f.session.open()); opened = true
                f.configure(pristine = true)
                f.prepareLostOriginalAndNewerLocal()
                f.reopenAndObtainActualReceiptReady()
            }
            f.attach(); block(f)
        } catch (t: Throwable) {
            failure = t
            if (f.scenario != null) try { failureScreenshot("assertion") } catch (capture: Throwable) { t.addSuppressed(capture) }
            throw t
        } finally {
            try {
                f.scenario?.close(); f.scenario = null
                main {
                    f.closeControllers()
                    value(if (opened) f.session.retireAndClose() else f.session.close())
                    NativeReviewedPostHostOwner.current = null; retainedFixture = null
                }
            } catch (cleanup: Throwable) { if (failure != null) failure.addSuppressed(cleanup) else throw cleanup }
        }
    }

    @Test fun reopenedNativeRecoveryReviewBackAndExplicitKeepPreserveExactOriginalWithoutThirdSend() = withRestoredHost { f ->
        val before = main { f.records() }
        val draftBefore = main { f.draftRecord() }
        val beforeJson = Json.parseToJsonElement(draftBefore.payload.copyForCodec().decodeToString()).jsonObject
        val exactNewerSnapshot = beforeJson.getValue("locals").jsonArray.single().jsonPrimitive.content
        val exactOriginalLink = beforeJson.getValue("publicationHold").jsonObject.getValue("originalLinkUtf8").jsonPrimitive.content
        val idsBefore = main { f.ids.toList() }
        // Independent real public review token: the native review below must replace it.
        // This is not an invented token or a claim that test code can read wrapper state.
        val prior = main { value(f.entry.prepareRestoredLocalRetention(f.root, f.newerRevision)) }
        assertEquals(NEWER, prior.snapshot.caption); assertNull(prior.snapshot.altText)
        assertNotNull(prior.snapshot.exactChoices)
        f.openRecovery()
        main {
            sameRecords(before, f.records()); assertEquals(idsBefore, f.ids)
            assertEquals(2, f.transport.calls.size); assertFalse(f.drafts.states.value.selected!!.localAcknowledged)
            assertFalse(f.publications.states.value.acknowledged)
            assertFalse(prior.isCurrentForNavigation)
        }
        screenshot("restored-native-review-read-only")
        back()
        await { main { f.drafts.states.value.screen == PostDraftScreen.LOCAL_LIST && !f.drafts.states.value.busy } }
        waitText("Private on this device", true)
        assertTrue(textNodes("Keep on this device").none { it.isVisibleToUser })
        main {
            val stale = f.entry.confirmRestoredLocalRetention(prior.token)
            assertEquals(FailureReason.CONFLICT, (stale as PortResult.Failure).reason)
            sameRecords(before, f.records()); assertEquals(idsBefore, f.ids)
            assertEquals(2, f.transport.calls.size); assertFalse(f.publications.states.value.acknowledged)
        }
        screenshot("restored-native-back-no-confirmation")
        click(NEWER)
        await { main { f.drafts.states.value.selected?.localRevision == f.newerRevision } }
        f.openRecovery()
        // Full choices and historical disclosure must remain visible, not just the caption.
        waitText("Your choices — kept unchanged", true)
        waitText("Earlier recipe-save disclosure", true)
        waitText("Synthetic test disclosure", true)
        click("Keep on this device")
        await { main { f.drafts.states.value.selected?.let {
            it.localAcknowledged && it.localRevision == f.newerRevision && it.caption == NEWER
        } == true && !f.drafts.states.value.busy } }
        main {
            val kept = f.draftRecord()
            assertEquals(draftBefore.revision + 1, kept.revision)
            assertEquals(draftBefore.schemaVersion, kept.schemaVersion)
            assertArrayEquals(draftBefore.payload.copyForCodec(), kept.payload.copyForCodec())
            val after = f.records()
            for ((key, row) in before) if (key.collection != "mealflow.post-drafts.v1") sameRecord(row, after[key])
            assertEquals(idsBefore, f.ids); assertEquals(2, f.transport.calls.size)
            assertEquals("RECEIPT_READY", f.commandPhase())
            assertFalse(f.drafts.states.value.serverAcknowledged)
            assertFalse(f.publications.states.value.acknowledged)
        }
        waitText("What did you make?", true); screenshot("restored-native-local-confirmed")
        val afterKeep = main { f.records() }
        top(); click("Review retained publication")
        // This original already has an actual durable reply. Opening its retained page only
        // observes history; RECEIPT_READY offers local confirmation, not another HTTP retry.
        waitText("Confirm received result on device", true)
        main {
            val observed = f.publications.states.value
            val pending = checkNotNull(observed.pending)
            assertEquals("RECEIPT_READY", pending.phase)
            assertEquals(f.original.idempotencyKey!!.use { it }, pending.commandId)
            assertEquals(2, pending.observedAttempts)
            assertNull(observed.retry); assertNull(observed.review)
            val history = observed.history.single { it.original.commandId == pending.commandId }
            assertEquals("pending", history.outcome)
            assertArrayEquals(f.original.body!!.copyForCodec(), history.original.exactOriginalPostWrite.encodeUtf8())
            assertEquals(f.original.idempotencyKey!!.use { it }, history.original.commandId)
            assertEquals(f.root, history.original.clientDraftId)
            val currentLocal = checkNotNull(f.drafts.states.value.selected)
            assertEquals(f.root, currentLocal.clientDraftId)
            assertEquals(f.newerRevision, currentLocal.localRevision)
            assertEquals(NEWER, currentLocal.caption); assertNull(currentLocal.altText)
            assertTrue(currentLocal.localAcknowledged)
            val currentJson = Json.parseToJsonElement(f.draftRecord().payload.copyForCodec().decodeToString()).jsonObject
            assertEquals(exactNewerSnapshot, currentJson.getValue("locals").jsonArray.single().jsonPrimitive.content)
            sameRecords(afterKeep, f.records()); assertEquals(idsBefore, f.ids)
            assertEquals(2, f.transport.calls.size); assertEquals(2, f.transport.completedCalls.size)
            assertFalse(f.drafts.states.value.serverAcknowledged)
            assertFalse(observed.acknowledged)
        }
        click("Confirm received result on device")
        await { main { f.publications.states.value.acknowledged && f.publications.states.value.phase == PostComposerPhase.PUBLISHED } }
        main {
            assertEquals(2, f.transport.calls.size); assertEquals(2, f.transport.completedCalls.size)
            assertEquals(idsBefore, f.ids); assertEquals(1, f.transport.publishedCount)
            sameOriginal(f.original, f.transport.calls.last())
            assertEquals(ORIGINAL, json(f.publications.states.value.exactCanonicalPost!!).getValue("caption").jsonPrimitive.content)
            value(f.drafts.restoreLocal())
            val remainder = f.drafts.states.value.unsubmittedRemainders.single()
            assertEquals(f.newerRevision, remainder.newerLocalRevision)
            assertFalse(remainder.editable); assertTrue(f.drafts.states.value.localDrafts.isEmpty())
            val closed = Json.parseToJsonElement(f.draftRecord().payload.copyForCodec().decodeToString()).jsonObject
            val retained = closed.getValue("remainders").jsonArray.single().jsonObject
            assertEquals(exactNewerSnapshot, retained.getValue("unsubmittedSnapshotUtf8").jsonPrimitive.content)
            assertEquals(exactOriginalLink, retained.getValue("originalLinkUtf8").jsonPrimitive.content)
        }
        waitText("Action confirmed", true); screenshot("restored-native-original-applied-no-third-send")
    }

    @Test fun nativeSessionInvalidationRemovesRecoveryConsentAndReopenStillHasUnacknowledgedExactBytes() = withRestoredHost { f ->
        val before = main { f.records() }
        val idsBefore = main { f.ids.toList() }
        f.openRecovery(); waitText("Keep on this device", true)
        // Retain an actual native action node, not a fabricated callback. The test attempts
        // it once only AFTER real session revocation, regardless of Android's accepted flag.
        val staleButton = textNodes("Keep on this device").asSequence().mapNotNull { label ->
            var node: AccessibilityNodeInfo? = label
            while (node != null && !node.isClickable) node = node.parent
            node?.takeIf { it.isVisibleToUser && it.isEnabled }
        }.distinct().single()
        screenshot("restored-native-before-session-revocation")
        main { f.identity.revokeBeforeSessionMutation(); value(f.session.invalidate()) }
        staleButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        await { main { f.drafts.states.value.phase == PostDraftPhase.UNAVAILABLE &&
            f.publications.states.value.phase == PostComposerPhase.UNAVAILABLE } }
        await { textNodes(NEWER).none { it.isVisibleToUser } &&
            textNodes("Keep on this device").none { it.isVisibleToUser } }
        main {
            assertNull(f.drafts.states.value.selected); assertFalse(f.publications.states.value.acknowledged)
            assertEquals(idsBefore, f.ids); assertEquals(2, f.transport.calls.size)
        }
        screenshot("restored-native-session-redacted")
        // Closing the actual page cancels its wrapper job. Reopen reads the exact same store,
        // so a late stale callback cannot be mistaken for successfully keeping anything.
        f.scenario!!.close(); f.scenario = null
        main {
            f.closeControllers(); NativeReviewedPostHostOwner.current = null
            value(f.session.reopenRetainedForTest()); f.configure(pristine = false)
            sameRecords(before, f.records())
            assertFalse(f.drafts.states.value.localDrafts.single().localAcknowledged)
            assertEquals(f.newerRevision, f.drafts.states.value.localDrafts.single().localRevision)
            assertEquals("RECEIPT_READY", f.commandPhase())
            assertEquals(idsBefore, f.ids); assertEquals(2, f.transport.completedCalls.size)
            assertFalse(f.publications.states.value.acknowledged)
        }
    }

    private fun sameRecords(expected: Map<RecordKey, PrivateRecord?>, actual: Map<RecordKey, PrivateRecord?>) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (key, value) -> sameRecord(value, actual[key]) }
    }
    private fun sameRecord(expected: PrivateRecord?, actual: PrivateRecord?) {
        if (expected == null) { assertNull(actual); return }
        assertNotNull(actual)
        assertEquals(expected.revision, actual!!.revision)
        assertEquals(expected.schemaVersion, actual.schemaVersion)
        assertArrayEquals(expected.payload.copyForCodec(), actual.payload.copyForCodec())
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
        failureScreenshot("transition"); fail("Timed out waiting for actual restored-retention native transition")
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
        val directory = File(instrumentation.targetContext.noBackupFilesDir, "restored-post-host-ui-evidence")
            .also { check(it.mkdirs() || it.isDirectory) }
        File(directory, name + ".png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    companion object {
        private var retainedFixture: Any? = null
        private const val ORIGINAL = "SYNTHETIC_NATIVE_ORIGINAL"
        private const val NEWER = "SYNTHETIC_NATIVE_RESTORED_NEWER"
    }
}
