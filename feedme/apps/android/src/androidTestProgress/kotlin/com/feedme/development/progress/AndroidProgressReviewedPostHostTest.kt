@file:OptIn(com.feedme.storage.SessionControlRecoveryCompositionApi::class)

package com.feedme.development.progress

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
import com.feedme.app.mealflow.MealFlowExperience
import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import com.feedme.mealflow.social.*
import com.feedme.session.*
import com.feedme.storage.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Separate connected progress-host acceptance candidate. Run AFTER the existing host suites
 * on their explicitly owned progress UID, with acknowledged terminal SIGNED_OUT control.
 * Real application owner, Activity, encrypted stores/runtime, integration, service and shared UI.
 * Identity/service are explicitly SYNTHETIC and self-only. No live provider, HTTP, process death,
 * transport/ID counter, or post-commit lost-response claim. Offline here means zero attempts.
 * No raw client-journal seed, replacement owner, app clear or inferred cleanup permission. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AndroidProgressReviewedPostHostTest {
    @get:Rule val testName = TestName()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val owner get() = (context.applicationContext as ProgressApplication).owner
    private val ui get() = instrumentation.uiAutomation
    private var failureSequence = 0
    private var clickSequence = 0
    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main.immediate) { block() } }
    private fun <T> value(result: PortResult<T>): T = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> error("Actual progress acknowledgement required: ${result.reason}")
    }
    private fun json(document: WireDocument) = Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
    private fun sameDocument(expected: WireDocument, actual: WireDocument) = assertArrayEquals(expected.encodeUtf8(), actual.encodeUtf8())
    private fun sameOriginal(expected: PublicationOriginalSnapshot, actual: PublicationOriginalSnapshot) {
        assertEquals(expected.commandId, actual.commandId)
        assertEquals(expected.clientDraftId, actual.clientDraftId)
        assertEquals(expected.originalCreatedAtMillis, actual.originalCreatedAtMillis)
        assertEquals(expected.originalCanonicalUserId, actual.originalCanonicalUserId)
        sameDocument(expected.exactOriginalPostWrite, actual.exactOriginalPostWrite)
        assertEquals(expected.originalReviewedLocal.clientDraftId, actual.originalReviewedLocal.clientDraftId)
        assertEquals(expected.originalReviewedLocal.localRevision, actual.originalReviewedLocal.localRevision)
        sameDocument(expected.originalReviewedLocal.exactHistoricalSnapshot, actual.originalReviewedLocal.exactHistoricalSnapshot)
        assertEquals(expected.displayedDisclosure.version, actual.displayedDisclosure.version)
        assertEquals(expected.displayedDisclosure.text, actual.displayedDisclosure.text)
    }

    private inner class Fixture {
        var admitted = false
        var created = false
        var scenario: ActivityScenario<ProgressActivity>? = null
        var seed: CookingOnlySeed? = null
        lateinit var experience: MealFlowExperience
        val entry get() = checkNotNull(experience.reviewedPosts)
        val drafts get() = entry.drafts
        val publications get() = entry.publications
        fun settled() = await { main { !experience.forms.value.busy && !drafts.states.value.busy } }
        fun open(start: Boolean = true) {
            main {
                check(retained == null) { "A previous unclosed progress-native fixture remains retained" }
                assertEquals(ProgressNativeInventory.PACKAGE, context.packageName)
                check(Build.VERSION.SDK_INT >= 27)
                check(owner.states.value.phase in setOf(ProgressHostPhase.NEW, ProgressHostPhase.CLOSED))
                assertEquals(ProgressInventoryKind.EXISTING, value(ProgressNativeInventory.inspect(context)))
                retained = this@Fixture; admitted = true // Before native I/O, never an erase grant.
                value(owner.inspectStartup()); assertEquals(ProgressHostPhase.RESUME_AVAILABLE, owner.states.value.phase)
                value(owner.resume()); assertEquals(ProgressHostPhase.START_AVAILABLE, owner.states.value.phase)
                assertNull(owner.boundary.current()); assertNull(owner.experience.value)
            }
            if (!start) { main { value(owner.close()) }; return }
            launch()
            click("Start preview")
            await { main { owner.states.value.phase == ProgressHostPhase.ACTIVE && owner.experience.value != null } }
            main {
                created = true; experience = checkNotNull(owner.experience.value)
                assertSame(experience.postDrafts, entry.drafts)
            }
            openDrafts()
        }
        fun launch() { scenario = ActivityScenario.launch(Intent(context, ProgressActivity::class.java)) }
        fun openDrafts() {
            click("My private drafts")
            await { main { experience.reviewedPostsNavigation.value.visible && !experience.forms.value.busy } }
            waitText("Start a fresh draft", true)
            main { assertEquals(PostDraftJournalFormat.CURRENT, drafts.states.value.journalFormat) }
        }
        fun newDraft(caption: String) {
            click("Start a fresh draft"); setText("What did you make?", caption)
            await { main { drafts.states.value.selected?.let { it.caption == caption && it.localAcknowledged } == true } }
        }
        fun edit(caption: String) {
            top(); setText("What did you make?", caption)
            await { main { drafts.states.value.selected?.let { it.caption == caption && it.localAcknowledged } == true } }
        }
        fun choices(privateSave: Boolean = false) {
            top(); click("Review audience & recipe saves"); waitText("Audience & recipe saves", true)
            click("Only me"); click("Do not keep on My Plate"); click("Do not allow recipe saves")
            click(if (privateSave) "A private Save" else "A publication")
            click("Load current disclosure"); waitText(ProgressPostPreviewContract.disclosureText, true)
            click("Keep these choices on device")
            await { main { drafts.states.value.selected?.let { it.requiresReviewedSave && it.localAcknowledged } == true } }
            waitText("What did you make?", true)
        }
        fun review(saved: Boolean = false): PublicationReviewView {
            top(); click("Review publication")
            click(if (saved) "Review this saved-draft publication" else "Review direct publication")
            await { main { publications.states.value.review != null } }
            return main { checkNotNull(publications.states.value.review) }
        }
        fun publish(): PublicationHistorySnapshot {
            click("Publish this post")
            await { main { publications.states.value.let { it.phase == PostComposerPhase.PUBLISHED && it.acknowledged } } }
            return main { publications.states.value.history.single { it.outcome == "published" } }
        }
        fun offlineOriginal(): PublicationOriginalSnapshot {
            main { owner.setServiceOffline(true); assertTrue(owner.states.value.serviceOffline) }
            click("Publish this post")
            // Explicit retained read after the actual OFFLINE refusal. It is never an ACK.
            click("Refresh retained publication history")
            await { main { publications.states.value.pending?.let { it.observedAttempts == 0 && it.phase == "AWAITING_CONFIRMATION" } == true } }
            click("Review original publication")
            await { main { publications.states.value.retry?.observedAttempts == 0 } }
            return main { checkNotNull(publications.states.value.retry).original }
        }
        fun close() {
            scenario?.close(); scenario = null
            main {
                seed?.let { it.close(); seed = null }
                if (created) {
                    // Only this case's actual Start/runtime.create established reset ownership.
                    if (owner.states.value.phase == ProgressHostPhase.CLOSED) value(owner.inspectStartup())
                    if (owner.states.value.phase == ProgressHostPhase.RESUME_AVAILABLE) value(owner.resume())
                    check(owner.states.value.canReset) { "Test identity retained; reset is not admitted" }
                    owner.requestReset(); value(owner.confirmReset(checkNotNull(owner.states.value.confirmation)))
                    assertEquals(ProgressHostPhase.CLOSED, owner.states.value.phase)
                    assertNull(owner.boundary.current()); assertNull(owner.experience.value)
                    value(owner.inspectStartup()); assertEquals(ProgressHostPhase.RESUME_AVAILABLE, owner.states.value.phase)
                    value(owner.resume()); assertEquals(ProgressHostPhase.START_AVAILABLE, owner.states.value.phase)
                    assertNull(owner.boundary.current()); assertNull(owner.experience.value)
                    value(owner.close())
                } else if (admitted) value(owner.close())
                if (admitted) retained = null // Only after acknowledged ordered cleanup.
            }
        }
    }
    private fun withHost(start: Boolean = true, block: (Fixture) -> Unit) {
        val fixture = Fixture(); var failure: Throwable? = null
        try { fixture.open(start); block(fixture) }
        catch (error: Throwable) {
            failure = error
            if (fixture.created && fixture.scenario != null) try { failureScreenshot("assertion") }
            catch (capture: Throwable) { error.addSuppressed(capture) }
            throw error
        } finally { try { fixture.close() } catch (cleanup: Throwable) {
            if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
        } }
    }

    @Test fun aActualProgressDirectReviewBackAndExplicitPublishKeepExactSelfOnlyContent() = withHost { f ->
        f.newDraft("PROGRESS_DIRECT_EXACT"); f.choices()
        val selected = main { checkNotNull(f.drafts.states.value.selected) }
        val first = f.review()
        main {
            assertTrue(first.snapshot.target is PublicationTarget.DirectLocal)
            assertNull(f.publications.states.value.pending); assertNull(f.publications.states.value.allocation)
            assertTrue(f.publications.states.value.history.isEmpty()); assertFalse(f.publications.states.value.acknowledged)
            assertEquals(selected.clientDraftId, first.snapshot.target.clientDraftId)
            assertEquals(selected.localRevision, first.snapshot.reviewedLocal.localRevision)
            assertEquals(ProgressPostPreviewContract.authorId, first.snapshot.publisherUserId)
            assertEquals(ProgressPostPreviewContract.disclosureText, first.snapshot.disclosure.text)
            val request = json(first.snapshot.exactProposedPostWrite)
            assertEquals("PROGRESS_DIRECT_EXACT", request.getValue("caption").jsonPrimitive.content)
            assertEquals(buildJsonObject { put("kind", "self"); put("circleIds", JsonArray(emptyList())) },
                request.getValue("audience"))
            assertEquals(JsonPrimitive(false), request.getValue("allowRecipeSaves"))
            assertFalse("draftId" in request); assertFalse("draftVersion" in request)
        }
        waitText("Publish this post", true); screenshot("progress-post-direct-review")
        back(); waitText("What did you make?", true)
        main {
            assertFalse(first.isCurrentForNavigation); assertNull(f.publications.states.value.review)
            assertNull(f.publications.states.value.pending); assertTrue(f.publications.states.value.history.isEmpty())
            assertEquals(selected.clientDraftId, f.drafts.states.value.selected!!.clientDraftId)
            assertEquals(selected.localRevision, f.drafts.states.value.selected!!.localRevision)
        }
        val confirmed = f.review(); val receipt = f.publish()
        main {
            sameDocument(confirmed.snapshot.exactProposedPostWrite, receipt.original.exactOriginalPostWrite)
            assertEquals(confirmed.snapshot.reviewedLocal.clientDraftId, receipt.original.clientDraftId)
            assertEquals("PROGRESS_DIRECT_EXACT", json(f.publications.states.value.exactCanonicalPost!!).getValue("caption").jsonPrimitive.content)
            sameDocument(receipt.exactCanonicalPost!!, f.publications.states.value.exactCanonicalPost!!)
            assertEquals(receipt.etag, f.publications.states.value.etag); assertNull(f.publications.states.value.pending)
            assertTrue(f.experience.reviewedPostsNavigation.value.visible) // Terminal root hide is not outer exit.
        }
        waitText("Action confirmed", true); screenshot("progress-post-direct-confirmed")
    }

    @Test fun bActualProgressPrivateCreateReviewedPatchAndSavedPublishStaySeparate() = withHost { f ->
        f.newDraft("PROGRESS_PRIVATE_INITIAL"); click("Save privately to server")
        await { main { f.drafts.states.value.let { it.serverAcknowledged && it.pending == null && !it.busy } } }
        val baseline = main { checkNotNull(f.drafts.states.value.selected!!.server) }
        main { assertTrue(f.publications.states.value.history.isEmpty()); assertFalse(f.publications.states.value.acknowledged) }
        f.edit("PROGRESS_PRIVATE_REVIEWED"); f.choices(privateSave = true)
        top(); click("Review private Save"); await { main { f.drafts.states.value.reviewedSave != null } }
        val save = main { checkNotNull(f.drafts.states.value.reviewedSave) }
        main {
            assertEquals(baseline.id, save.snapshot.target.draftId); assertEquals(baseline.etag, save.snapshot.target.etag)
            assertEquals("PROGRESS_PRIVATE_REVIEWED", json(save.snapshot.exactProposedPatch).getValue("caption").jsonPrimitive.content)
            assertNull(f.drafts.states.value.pending); assertTrue(f.publications.states.value.history.isEmpty())
        }
        waitText("Confirm private Save", true); screenshot("progress-post-private-save-review")
        click("Confirm private Save")
        await { main { f.drafts.states.value.let { it.serverAcknowledged && it.pending == null && !it.busy } } }
        val saved = main { checkNotNull(f.drafts.states.value.selected!!.server) }
        main {
            assertEquals(baseline.id, saved.id); assertNotEquals(baseline.etag, saved.etag)
            assertEquals("PROGRESS_PRIVATE_REVIEWED", saved.caption)
            assertTrue(f.publications.states.value.history.isEmpty()); assertFalse(f.publications.states.value.acknowledged)
        }
        // Genuine Save→review without a dummy edit, refresh, reopen or cleanup action.
        val publication = f.review(saved = true)
        main {
            assertTrue(publication.snapshot.target is PublicationTarget.SavedDraft)
            val target = publication.snapshot.target as PublicationTarget.SavedDraft
            assertEquals(saved.id, target.draftId); assertEquals(saved.etag, target.etag)
            val request = json(publication.snapshot.exactProposedPostWrite)
            assertEquals(saved.id, request.getValue("draftId").jsonPrimitive.content)
            assertEquals(target.draftVersion.decimal, request.getValue("draftVersion").jsonPrimitive.content)
            assertEquals("PROGRESS_PRIVATE_REVIEWED", request.getValue("caption").jsonPrimitive.content)
        }
        val receipt = f.publish()
        main {
            sameDocument(publication.snapshot.exactProposedPostWrite, receipt.original.exactOriginalPostWrite)
            assertEquals("PROGRESS_PRIVATE_REVIEWED", json(receipt.exactCanonicalPost!!).getValue("caption").jsonPrimitive.content)
        }
        waitText("Action confirmed", true); screenshot("progress-post-saved-confirmed")
    }

    @Test fun cOfflineZeroAttemptOriginalRequiresExplicitExactRetryNotReplacement() = withHost { f ->
        f.newDraft("PROGRESS_OFFLINE_ORIGINAL"); f.choices(); val review = f.review()
        val original = f.offlineOriginal()
        main {
            sameDocument(review.snapshot.exactProposedPostWrite, original.exactOriginalPostWrite)
            assertEquals(original.commandId, f.publications.states.value.pending!!.commandId)
            assertFalse(f.publications.states.value.acknowledged)
            assertFalse("draftId" in json(original.exactOriginalPostWrite)) // Direct branch remains exact.
        }
        waitText("Retry this original publication", true); screenshot("progress-post-offline-original")
        back(); waitText("What did you make?", true)
        main {
            assertEquals(original.commandId, f.publications.states.value.pending!!.commandId)
            assertEquals(0, f.publications.states.value.pending!!.observedAttempts)
            assertFalse(f.publications.states.value.acknowledged)
            owner.setServiceOffline(false)
        }
        top(); click("Review retained publication"); click("Review original publication")
        await { main { f.publications.states.value.retry != null } }
        main { sameOriginal(original, f.publications.states.value.retry!!.original); assertEquals(0, f.publications.states.value.retry!!.observedAttempts) }
        click("Retry this original publication")
        await { main { f.publications.states.value.let { it.acknowledged && it.phase == PostComposerPhase.PUBLISHED } } }
        main {
            val published = f.publications.states.value.history.single { it.outcome == "published" }
            sameOriginal(original, published.original); assertNull(f.publications.states.value.pending)
            assertEquals("PROGRESS_OFFLINE_ORIGINAL", json(published.exactCanonicalPost!!).getValue("caption").jsonPrimitive.content)
        }
        waitText("Action confirmed", true); screenshot("progress-post-exact-original-confirmed")
    }

    @Test fun dActivityRecreationAndAcknowledgedOwnerResumeRetainOriginalWithoutSending() = withHost { f ->
        f.newDraft("PROGRESS_OWNER_REOPEN"); f.choices(); f.review(); val original = f.offlineOriginal()
        val firstExperience = f.experience
        val oldLease = main { checkNotNull(owner.boundary.current()) }
        val oldReview = main { checkNotNull(f.publications.states.value.retry) }
        f.scenario!!.recreate(); waitText("What did you make?", true)
        main {
            assertSame(firstExperience, owner.experience.value)
            assertSame(oldLease, owner.boundary.current()); assertSame(oldReview, f.publications.states.value.retry)
            sameOriginal(original, f.publications.states.value.retry!!.original)
            assertFalse(f.publications.states.value.acknowledged)
        }
        // The application retains controllers, not the old Activity's remembered page.
        // Explicitly reopen the retained original instead of requiring automatic routing.
        top(); click("Review retained publication"); click("Review original publication")
        await { main { f.publications.states.value.retry != null } }
        main { sameOriginal(original, f.publications.states.value.retry!!.original); assertFalse(f.publications.states.value.acknowledged) }
        waitText("Retry this original publication", true)
        screenshot("progress-post-activity-retained")
        val liveAtClose = main { checkNotNull(f.publications.states.value.retry).also { assertTrue(it.isCurrentForNavigation) } }
        f.scenario!!.close(); f.scenario = null
        main {
            value(owner.close()); assertNull(owner.experience.value); assertNull(owner.boundary.current())
            assertFalse(liveAtClose.isCurrentForNavigation)
            assertEquals(PostComposerPhase.UNAVAILABLE, firstExperience.reviewedPosts!!.publications.states.value.phase)
            value(owner.inspectStartup()); assertEquals(ProgressHostPhase.RESUME_AVAILABLE, owner.states.value.phase)
            value(owner.resume()); assertEquals(ProgressHostPhase.ACTIVE, owner.states.value.phase)
            f.experience = checkNotNull(owner.experience.value)
            assertNotSame(firstExperience, f.experience); assertNotSame(oldLease, owner.boundary.current())
            assertFalse(f.publications.states.value.acknowledged)
        }
        f.launch(); f.openDrafts()
        // Actual historical restore already read the original; opening is not sending or ACK.
        main {
            assertEquals(original.commandId, f.publications.states.value.pending!!.commandId)
            assertEquals(0, f.publications.states.value.pending!!.observedAttempts)
            sameOriginal(original, f.publications.states.value.history.single().original)
            assertFalse(f.publications.states.value.acknowledged)
        }
        click("PROGRESS_OWNER_REOPEN"); top(); click("Review retained publication")
        click("Review original publication"); await { main { f.publications.states.value.retry != null } }
        main {
            sameOriginal(original, f.publications.states.value.retry!!.original)
            assertEquals(0, f.publications.states.value.retry!!.observedAttempts)
            assertFalse(f.publications.states.value.acknowledged)
        }
        waitText("Retry this original publication", true); screenshot("progress-post-native-owner-resumed")
        // Deliberately no confirm in this case. Cleanup is separately explicit preview reset.
    }

    @Test fun eExplicitResetRevokesPreparedReviewAndOldEntryBeforeNewIdentity() = withHost { f ->
        val marker = "PROGRESS_RESET_PRIVATE_REVIEW"
        f.newDraft(marker); f.choices(); val review = f.review()
        val oldEntry = f.entry; val oldExperience = f.experience
        val oldLease = main { checkNotNull(owner.boundary.current()) }
        click("Options"); click("Reset preview"); waitText("Reset this synthetic preview?", true)
        main {
            assertNotNull(owner.states.value.confirmation); assertSame(oldLease, owner.boundary.current())
            assertTrue(review.isCurrentForNavigation); assertFalse(oldEntry.publications.states.value.acknowledged)
        }
        screenshot("progress-post-reset-consent")
        click("Keep retained state")
        main { assertNull(owner.states.value.confirmation); assertTrue(review.isCurrentForNavigation) }
        click("Options"); click("Reset preview"); click("Confirm reset")
        await { main { owner.states.value.phase == ProgressHostPhase.CLOSED && owner.experience.value == null } }
        main {
            f.created = false // This exact test-created identity was actually retired above.
            assertNull(owner.boundary.current()); assertFalse(review.isCurrentForNavigation)
            assertEquals(PostComposerPhase.UNAVAILABLE, oldEntry.publications.states.value.phase)
            assertFalse(oldEntry.publications.states.value.acknowledged)
            assertNull(oldEntry.publications.states.value.exactCanonicalPost)
            assertTrue(oldEntry.publications.states.value.history.isEmpty())
            assertNull(oldEntry.drafts.states.value.selected); assertNull(oldExperience.forms.value.values)
            val stale = oldEntry.publications.confirmPublish(review.token)
            assertTrue(stale is PortResult.Failure)
            assertFalse(oldEntry.publications.states.value.acknowledged)
        }
        await { textNodes(marker).none { it.isVisibleToUser } }; screenshot("progress-post-reset-redacted")
        click("Inspect retained state"); click("Resume local preview")
        await { main { owner.states.value.phase == ProgressHostPhase.START_AVAILABLE } }
        click("Start preview")
        await { main { owner.states.value.phase == ProgressHostPhase.ACTIVE && owner.experience.value != null } }
        main {
            f.created = true; f.experience = checkNotNull(owner.experience.value)
            assertNotSame(oldExperience, f.experience); assertNotSame(oldEntry, f.entry)
            assertNotSame(oldLease, owner.boundary.current()); assertFalse(review.isCurrentForNavigation)
        }
        f.openDrafts()
        main {
            assertTrue(f.drafts.states.value.localDrafts.isEmpty()); assertTrue(f.publications.states.value.history.isEmpty())
            assertNull(f.publications.states.value.pending); assertFalse(f.publications.states.value.acknowledged)
            assertTrue(oldEntry.publications.confirmPublish(review.token) is PortResult.Failure)
        }
    }

    @Test fun fMissingSocialLedgerResumeKeepsActualCookingWithoutInitializationOrReset() = withHost(start = false) { f ->
        val seed = CookingOnlySeed().also { f.seed = it }
        main {
            seed.open(); seed.createIdentity { f.created = true }; seed.initializeCoreOnly()
            seed.assertNoSocialLedger(); seed.close(); f.seed = null
            value(owner.inspectStartup()); assertEquals(ProgressHostPhase.RESUME_AVAILABLE, owner.states.value.phase)
            value(owner.resume()); assertEquals(ProgressHostPhase.ACTIVE, owner.states.value.phase)
            // Owned test-harness connectivity persists across cases and owner close/reset.
            owner.setServiceOffline(false); assertFalse(owner.states.value.serviceOffline)
            f.experience = checkNotNull(owner.experience.value)
            assertNull(f.experience.reviewedPosts); assertNull(f.experience.postDrafts)
            assertFalse(f.experience.reviewedPostsNavigation.value.visible)
            assertNull(owner.states.value.confirmation); assertNotNull(owner.boundary.current())
        }
        f.launch(); waitText("What’s the", true)
        main {
            val experience = f.experience
            value(experience.refreshContext())
            experience.edit { it.copy(mode = MealMode.ASSEMBLE, energy = MealEnergy.ASSEMBLE, servings = "1",
                totalMinutes = "5", ingredientIds = listOf(ProgressCatalog.ingredientId), equipmentIds = listOf("bowl"), tasteTags = listOf("crunch")) }
            assertEquals(MealFlowPhase.READY, value(experience.findMeal()).phase)
            assertNotNull(experience.meals.states.value.plan)
            value(experience.recipe())
            assertNull(experience.cooking.states.value.cooking)
        }
        click("Cook this plan"); waitText("Start this exact plan?", true)
        main { assertNull(f.experience.cooking.states.value.cooking); assertNull(owner.states.value.confirmation) }
        screenshot("progress-post-absent-social-cooking-consent")
        click("Confirm cooking start")
        await { main { f.experience.cooking.states.value.let { it.phase == CookingFlowPhase.COOKING && it.serverAcknowledged } } }
        main { assertNull(f.experience.reviewedPosts); assertNull(f.experience.postDrafts); assertEquals(ProgressHostPhase.ACTIVE, owner.states.value.phase) }
        waitText("One step at a time.", true); screenshot("progress-post-absent-social-cooking")
        f.scenario!!.close(); f.scenario = null
        main {
            value(owner.close())
            val observer = CookingOnlySeed().also { f.seed = it }
            observer.open(); observer.restoreIdentity(); observer.assertNoSocialLedger(); observer.close(); f.seed = null
            // Read-only native reopen proves Resume/cooking did not initialize the absent ledger.
            // The fixture's final cleanup will separately Resume and explicitly reset its identity.
        }
    }

    /** Native prerequisite/observation only. Uses the actual core ledger, never the social
     * wrapper's initializing open. No raw seed or deletion of any client/service record. */
    private inner class CookingOnlySeed {
        private val boundary = SessionBoundary()
        private var composition: SessionApplicationComposition? = null
        private var reservation: SessionCompositionReservation? = null
        private var control: EncryptedSessionControlStore? = null
        private var work: EncryptedSessionWorkStore? = null
        private var data: EncryptedStateDatabase? = null
        private var credentials: AndroidCredentialStore? = null
        private var runtime: PrivateSessionRuntime? = null
        private var access: PrivateSessionAccess? = null
        private var core: ProgressServiceLedger? = null
        private var acquisitionUncertain = false
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
            acquire({ PrivateSessionRuntime.openReserved(reservation!!, control!!, work!!, data!!, credentials!!,
                ProgressIdentity, NativeWorkCancellationPort { PortResult.Failure(FailureReason.NOT_CONFIGURED) },
                NativeWorkIdSource { UUID.randomUUID().toString() },
                NativeWorkExecutionPolicy { _, _, _, _ -> PortResult.Failure(FailureReason.NOT_CONFIGURED) }) }) { runtime = it }
        }
        suspend fun createIdentity(created: () -> Unit) {
            assertEquals(PrivateSessionPhase.SIGNED_OUT, value(runtime!!.recover()))
            access = value(runtime!!.create()); created()
            assertEquals(ProgressIdentity.scope, access!!.scope)
        }
        suspend fun restoreIdentity() {
            assertEquals(PrivateSessionPhase.RESTORE_REQUIRED, value(runtime!!.recover()))
            access = value(runtime!!.restore())
        }
        private suspend fun requireCurrent() {
            val actual = checkNotNull(access)
            check(boundary.isCurrent(actual.lease) && runtime!!.currentAccess() === actual)
            check(actual.scope == ProgressIdentity.scope && actual.mode == PrivateSessionAccessMode.ONLINE)
            check(ProgressIdentity.matches(value(actual.credentials.read(actual.scope))))
            check(boundary.isCurrent(actual.lease) && runtime!!.currentAccess() === actual)
        }
        suspend fun initializeCoreOnly() {
            val actual = checkNotNull(access)
            core = ProgressServiceLedger(actual.store, actual.scope, actual.originBinding,
                EpochClock { System.currentTimeMillis() }, ::requireCurrent)
            value(core!!.open(allowInitialize = true))
        }
        suspend fun assertNoSocialLedger() {
            requireCurrent(); val actual = checkNotNull(access)
            assertNull(value(actual.store.read(actual.scope, ProgressPostServiceLedger.KEY)))
            requireCurrent()
        }
        suspend fun close() = withContext(NonCancellable + Dispatchers.Main.immediate) {
            core?.let { value(it.close()); core = null }
            runtime?.let { value(it.close()); runtime = null; access = null }
            work?.let { value(it.close()); work = null }; data?.let { value(it.close()); data = null }
            credentials?.let { value(it.close()); credentials = null }; control?.let { value(it.close()); control = null }
            check(!acquisitionUncertain) { "Unreturned native owner remains retained; no replacement/cleanup inference" }
            reservation?.let { value(it.release()); reservation = null }; composition?.let { value(it.close()); composition = null }
        }
        private suspend fun <T> acquire(open: suspend () -> PortResult<T>, retain: (T) -> Unit) {
            currentCoroutineContext().ensureActive(); check(!acquisitionUncertain); acquisitionUncertain = true
            withContext(NonCancellable) { retain(value(open())); acquisitionUncertain = false }
            currentCoroutineContext().ensureActive()
        }
    }

    private fun await(test: () -> Boolean) {
        repeat(100) { instrumentation.waitForIdleSync(); if (test()) return; SystemClock.sleep(100) }
        failureScreenshot("transition"); fail("Timed out waiting for actual connected progress transition")
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
        repeat(8) { SystemClock.sleep(20); event(MotionEvent.ACTION_MOVE, to) } // End the drag without an unintended fling.
        event(MotionEvent.ACTION_UP, to); SystemClock.sleep(150)
    }
    private fun top() { repeat(24) { if (textNodes("← Back").any { it.isVisibleToUser }) return; scroll(false) }; waitText("← Back") }
    private fun waitText(text: String, scroll: Boolean = false) {
        repeat(40) { attempt ->
            instrumentation.waitForIdleSync(); if (textNodes(text).any { it.isVisibleToUser }) return
            if (scroll && attempt >= 4) scroll(true)
            SystemClock.sleep(100)
        }
        failureScreenshot("text"); fail("Expected visible connected progress control: $text")
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
                check(candidates.size <= 1) { "Ambiguous connected progress action: $text; invocation=$invocation" }
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
        }.firstOrNull() ?: error("No actual editable connected progress accessibility owner")
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
        val directory = File(context.cacheDir, "progress-reviewed-post-ui-evidence")
            .also { check(it.mkdirs() || it.isDirectory) }
        File(directory, name + ".png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    companion object { private var retained: Any? = null }
}
