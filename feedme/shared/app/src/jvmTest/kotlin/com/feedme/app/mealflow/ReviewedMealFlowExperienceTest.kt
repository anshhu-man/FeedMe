package com.feedme.app.mealflow

import com.feedme.core.ports.*
import com.feedme.mealflow.*
import com.feedme.mealflow.social.*
import com.feedme.storage.PostDraftHttpSessionFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Actual encrypted SQLite and PrivateSessionRuntime access, with explicit synthetic credentials
 * and principal/disclosure adapters. No seeded social journals, native keystore, live provider,
 * remote publication, UI rendering or OS-process-restart acceptance is claimed. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewedMealFlowExperienceTest {
    @Test fun requiredBuilderReceivesTheOneAccessBeforeAnyConstructionSideEffect() = runTest { fixture { f ->
        val writes = f.session.localWriteStatements
        val experience = f.reviewed()
        val entry = assertNotNull(experience.reviewedPosts)
        assertSame(experience.postDrafts, entry.drafts)
        assertEquals(1, f.builders); assertEquals(0, f.ids); assertEquals(0, f.sends)
        assertEquals(writes, f.session.localWriteStatements)
        assertEquals(0, f.source.resolves); assertEquals(0, f.integration.disclosures)
        assertFalse(experience.reviewedPostsNavigation.value.visible)
        assertEquals(PostDraftJournalFormat.NOT_OBSERVED, entry.drafts.states.value.journalFormat)
        assertEquals(PostDraftScreen.HIDDEN, entry.drafts.states.value.screen)
        assertNull(entry.publications.states.value.review)
        // Both purpose-specific actual bindings must match the builder's identical access.
        val local = value(entry.drafts.newLocalDraft("Actual locally retained dinner")).selected!!
        assertTrue(local.localAcknowledged); assertEquals(1, f.ids)
        assertEquals(PostDraftJournalFormat.CURRENT, entry.drafts.states.value.journalFormat)
        assertEquals(ReviewedDraftFormat.CURRENT_TEXT, value(entry.inspectSelected(local.clientDraftId, local.localRevision)).format)
        value(entry.loadDisclosure(local.clientDraftId, ReviewedPostPurpose.PRIVATE_SAVE))
        value(entry.loadDisclosure(local.clientDraftId, ReviewedPostPurpose.PUBLICATION))
        assertEquals(setOf(ReviewedPostPurpose.PRIVATE_SAVE, ReviewedPostPurpose.PUBLICATION), f.integration.purposes.toSet())
        assertTrue(f.source.resolves > 0); assertEquals(2, f.source.bindings.size)
        assertEquals(0, f.sends)
    } }

    @Test fun explicitOpenRestoresOnlyAfterUserActionAndKeepsRouteIndependentOfDraftScreen() = runTest { fixture { f ->
        val experience = f.reviewed(); val entry = experience.reviewedPosts!!
        val writes = f.session.localWriteStatements
        assertEquals(0, f.source.resolves)
        value(experience.openPostDrafts())
        assertTrue(experience.reviewedPostsNavigation.value.visible)
        assertFalse(experience.reviewedPostsNavigation.value.opening)
        assertEquals(PostDraftScreen.LOCAL_LIST, entry.drafts.states.value.screen)
        assertEquals(PostDraftJournalFormat.CURRENT, entry.drafts.states.value.journalFormat)
        assertFalse(entry.publications.states.value.acknowledged)
        assertEquals(writes, f.session.localWriteStatements); assertEquals(0, f.ids); assertEquals(0, f.sends)
        // A child HIDDEN state does not unmount the reviewed route. Actual result/history
        // transitions use the same independent route; this is not a fabricated publish ACK.
        value(entry.drafts.back())
        assertEquals(PostDraftScreen.HIDDEN, entry.drafts.states.value.screen)
        assertTrue(experience.reviewedPostsNavigation.value.visible)
        value(experience.leavePostDrafts())
        assertFalse(experience.reviewedPostsNavigation.value.visible)
        assertEquals(writes, f.session.localWriteStatements); assertEquals(0, f.sends)
    } }

    @Test fun currentTextCreationAndExplicitCompleteChoicesRemainAvailable() = runTest { fixture { f ->
        val experience = f.reviewed(); value(experience.openPostDrafts())
        val entry = experience.reviewedPosts!!
        val first = value(entry.drafts.newLocalDraft("Before")).selected!!
        value(entry.drafts.editCaption(first.clientDraftId, "Exact newer dinner"))
        val selected = entry.drafts.states.value.selected!!
        val disclosure = value(entry.loadDisclosure(selected.clientDraftId, ReviewedPostPurpose.PUBLICATION))
        val choices = ReviewedPostChoices("Exact newer dinner", OptionalValue.Present(""), emptyList(),
            PublicationAudience.OnlyYou, false, OptionalValue.Absent, false, disclosure, OptionalValue.Absent)
        val kept = value(entry.editChoices(selected.clientDraftId, selected.localRevision, choices)).selected!!
        assertTrue(kept.localAcknowledged); assertEquals(selected.localRevision + 1, kept.localRevision)
        val inspected = value(entry.inspectSelected(kept.clientDraftId, kept.localRevision))
        assertEquals(ReviewedDraftFormat.CURRENT_COMPOSER, inspected.format)
        assertEquals("", inspected.altText); assertNotNull(inspected.exactChoices)
        assertEquals(disclosure.text, inspected.historicalDisclosure!!.text)
        assertEquals(1, f.ids); assertEquals(0, f.sends)
        assertTrue(experience.reviewedPostsNavigation.value.visible)
    } }

    @Test fun originalFactoriesKeepTheirAbsentAndLegacyDraftBehavior() = runTest { fixture { f ->
        val plain = f.legacy(withDrafts = false)
        assertNull(plain.postDrafts); assertNull(plain.reviewedPosts)
        assertEquals(FailureReason.UNAVAILABLE, assertIs<PortResult.Failure>(plain.openPostDrafts()).reason)
        value(plain.close()); f.experiences.remove(plain)
        val legacy = f.legacy(withDrafts = true)
        assertNull(legacy.reviewedPosts)
        value(legacy.openPostDrafts())
        assertEquals(PostDraftScreen.LOCAL_LIST, legacy.postDrafts!!.states.value.screen)
        val created = value(legacy.postDrafts!!.newLocalDraft("Legacy exact caption")).selected!!
        assertTrue(created.localAcknowledged)
        assertEquals(1, f.record().schemaVersion)
        assertEquals(PostDraftJournalFormat.LEGACY, legacy.postDrafts!!.states.value.journalFormat)
        assertFalse(legacy.reviewedPostsNavigation.value.visible)
        assertEquals(0, f.builders); assertEquals(0, f.sends)
    } }

    @Test fun configuredRestoreDoesNotMigrateOrDiscardAnExistingLegacyRow() = runTest { fixture { f ->
        val legacy = f.legacy(withDrafts = true)
        val local = value(legacy.postDrafts!!.newLocalDraft("Legacy preserved\nexact", "")).selected!!
        val before = f.record()
        value(legacy.close()); f.experiences.remove(legacy)
        val writes = f.session.localWriteStatements; val ids = f.ids
        val experience = f.reviewed()
        assertEquals(FailureReason.NOT_CONFIGURED, assertIs<PortResult.Failure>(experience.openPostDrafts()).reason)
        assertTrue(experience.reviewedPostsNavigation.value.visible)
        assertFalse(experience.reviewedPostsNavigation.value.opening)
        assertEquals(FailureReason.NOT_CONFIGURED, experience.reviewedPostsNavigation.value.failure)
        val entry = experience.reviewedPosts!!
        value(entry.drafts.openLocal(local.clientDraftId))
        val observed = value(entry.inspectSelected(local.clientDraftId, local.localRevision))
        assertEquals(ReviewedDraftFormat.LEGACY_TEXT, observed.format)
        assertTrue(observed.needsUpgrade)
        assertEquals(PostDraftJournalFormat.LEGACY, entry.drafts.states.value.journalFormat)
        assertEquals(local.caption, observed.caption); assertEquals(local.altText, observed.altText)
        assertEquals(before.schemaVersion, f.record().schemaVersion)
        assertEquals(before.revision, f.record().revision)
        assertContentEquals(before.payload.copyForCodec(), f.record().payload.copyForCodec())
        assertEquals(writes, f.session.localWriteStatements); assertEquals(ids, f.ids); assertEquals(0, f.sends)
        // The preview caller selects CURRENT_FORMAT_ONLY at its wrapper callsite. No
        // prepareUpgrade/confirmUpgrade action is invoked by construction/open/inspection.
    } }

    @Test fun wholeBundleCloseRedactsAllActualChildrenWithoutClosingBorrowedSession() = runTest { fixture { f ->
        val experience = f.reviewed(); val entry = experience.reviewedPosts!!
        value(experience.openPostDrafts())
        value(entry.drafts.newLocalDraft("Retained across UI-owner close"))
        val before = f.record(); val writes = f.session.localWriteStatements
        value(experience.close()); value(experience.close()); f.experiences.remove(experience)
        assertEquals(PostDraftPhase.UNAVAILABLE, entry.drafts.states.value.phase)
        assertEquals(PostDraftJournalFormat.NOT_OBSERVED, entry.drafts.states.value.journalFormat)
        assertEquals(PostComposerPhase.UNAVAILABLE, entry.publications.states.value.phase)
        assertEquals(CookbookPhase.UNAVAILABLE, experience.cookbook.states.value.phase)
        assertEquals(CookingFlowPhase.UNAVAILABLE, experience.cooking.states.value.phase)
        assertEquals(KitchenInputPhase.UNAVAILABLE, experience.kitchen.states.value.phase)
        assertEquals(MealFlowPhase.UNAVAILABLE, experience.meals.states.value.phase)
        assertEquals(IngredientPickerPhase.UNAVAILABLE, experience.ingredients.states.value.searchPhase)
        assertFalse(experience.reviewedPostsNavigation.value.visible)
        assertTrue(f.session.boundary.isCurrent(f.session.access.lease))
        assertContentEquals(before.payload.copyForCodec(), f.record().payload.copyForCodec())
        assertEquals(writes, f.session.localWriteStatements)
        assertIs<PortResult.Failure>(entry.drafts.newLocalDraft("A stale child cannot write"))
        val replacement = f.reviewed(); value(replacement.openPostDrafts())
        assertEquals("Retained across UI-owner close", replacement.postDrafts!!.states.value.localDrafts.single().caption)
        assertEquals(writes, f.session.localWriteStatements); assertEquals(0, f.sends)
    } }

    @Test fun throwingBuilderAndThrowingIntegrationGetterAcquireNoChildrenOrPerformIo() = runTest { fixture { f ->
        val writes = f.session.localWriteStatements
        assertFailsWith<IllegalStateException> { f.reviewed { error("Synthetic constructor refusal") } }
        assertFailsWith<IllegalStateException> { f.reviewed { access ->
            val valid = f.integrationFor(access)
            object : ReviewedKitchenIntegration by valid {
                override val delivery: PostPublicationDeliveryIntegration get() = error("Synthetic property refusal")
            }
        } }
        assertEquals(writes, f.session.localWriteStatements); assertEquals(0, f.ids); assertEquals(0, f.sends)
        assertEquals(0, f.source.resolves)
        val valid = f.reviewed(); value(valid.openPostDrafts())
        assertTrue(valid.reviewedPostsNavigation.value.visible)
    } }

    @Test fun builderInvalidatingActualLeaseIsRejectedBeforeChildAssembly() = runTest { fixture { f ->
        val writes = f.session.localWriteStatements
        assertFailsWith<IllegalArgumentException> { f.reviewed { access ->
            val integration = f.integrationFor(access)
            f.source.revoke(); f.session.boundary.clear()
            integration
        } }
        assertTrue(f.experiences.isEmpty())
        assertEquals(0, f.source.resolves); assertEquals(0, f.ids); assertEquals(0, f.sends)
        assertEquals(writes, f.session.localWriteStatements)
    } }

    @Test fun backWhileActualPrincipalReadIsSuspendedCancelsOpenAndRejectsLateRouteDelivery() = runTest { fixture { f ->
        val experience = f.reviewed(); val gate = CompletableDeferred<Unit>()
        f.source.gate = gate
        val writes = f.session.localWriteStatements
        val opening = async { experience.openPostDrafts() }
        runCurrent(); assertTrue(experience.reviewedPostsNavigation.value.opening)
        assertTrue(f.source.resolves > 0)
        value(experience.leavePostDrafts())
        assertFalse(experience.reviewedPostsNavigation.value.visible)
        gate.complete(Unit); opening.join()
        assertTrue(opening.isCancelled)
        assertFalse(experience.reviewedPostsNavigation.value.visible)
        assertNull(experience.reviewedPosts!!.publications.states.value.review)
        assertEquals(writes, f.session.localWriteStatements); assertEquals(0, f.ids); assertEquals(0, f.sends)
        f.source.gate = null
        value(experience.openPostDrafts()); assertTrue(experience.reviewedPostsNavigation.value.visible)
    } }

    @Test fun actualRuntimeReopenDuringSuspendedOpenCannotShowTheOldSessionRoute() = runTest { fixture { f ->
        val experience = f.reviewed(); val gate = CompletableDeferred<Unit>()
        f.source.gate = gate
        val opening = async { experience.openPostDrafts() }
        runCurrent(); assertTrue(experience.reviewedPostsNavigation.value.opening)
        val oldLease = f.session.access.lease
        f.source.revoke(); f.session.reopen()
        assertNotSame(oldLease, f.session.access.lease)
        gate.complete(Unit); opening.join()
        assertTrue(opening.isCancelled); assertFalse(experience.reviewedPostsNavigation.value.visible)
        assertEquals(PostDraftPhase.UNAVAILABLE, experience.postDrafts!!.states.value.phase)
        assertEquals(0, f.ids); assertEquals(0, f.sends)
        assertEquals(FailureReason.STALE_SESSION, assertIs<PortResult.Failure>(experience.openPostDrafts()).reason)
    } }

    @Test fun closeDuringSuspendedOpenKeepsAllChildrenClosedAfterLateCallback() = runTest { fixture { f ->
        val experience = f.reviewed(); val gate = CompletableDeferred<Unit>()
        f.source.gate = gate
        val opening = async { experience.openPostDrafts() }
        runCurrent(); assertTrue(experience.reviewedPostsNavigation.value.opening)
        value(experience.close())
        gate.complete(Unit); opening.join()
        assertTrue(opening.isCancelled)
        assertFalse(experience.reviewedPostsNavigation.value.visible)
        assertEquals(PostComposerPhase.UNAVAILABLE, experience.reviewedPosts!!.publications.states.value.phase)
        assertTrue(f.session.boundary.isCurrent(f.session.access.lease))
        assertEquals(0, f.ids); assertEquals(0, f.sends)
    } }

    @Test fun unavailableMappingLeavesExplicitErrorAndNeverRetriesOnStateReadOrBack() = runTest { fixture { f ->
        val experience = f.reviewed(); f.source.refuse = true
        val writes = f.session.localWriteStatements
        assertEquals(FailureReason.NOT_CONFIGURED, assertIs<PortResult.Failure>(experience.openPostDrafts()).reason)
        assertTrue(experience.reviewedPostsNavigation.value.visible)
        assertEquals(FailureReason.NOT_CONFIGURED, experience.reviewedPostsNavigation.value.failure)
        val resolves = f.source.resolves
        repeat(3) { experience.reviewedPostsNavigation.value; experience.postDrafts!!.states.value }
        value(experience.leavePostDrafts())
        assertEquals(resolves, f.source.resolves)
        assertEquals(writes, f.session.localWriteStatements); assertEquals(0, f.ids); assertEquals(0, f.sends)
    } }

    @Test fun formatIsUnknownUntilAnActualReadAndValidationKeepsTheObservedFormat() = runTest { fixture { f ->
        val experience = f.reviewed(); val drafts = experience.postDrafts!!
        val writes = f.session.localWriteStatements
        assertEquals(PostDraftJournalFormat.NOT_OBSERVED, drafts.states.value.journalFormat)
        value(drafts.back())
        assertEquals(PostDraftJournalFormat.NOT_OBSERVED, drafts.states.value.journalFormat)
        assertEquals(writes, f.session.localWriteStatements)
        value(experience.openPostDrafts())
        assertEquals(PostDraftJournalFormat.CURRENT, drafts.states.value.journalFormat)
        val local = value(drafts.newLocalDraft("Retained format")).selected!!
        val before = f.record()
        assertIs<PortResult.Failure>(drafts.editCaption(local.clientDraftId, "x".repeat(5000)))
        assertEquals(PostDraftJournalFormat.CURRENT, drafts.states.value.journalFormat)
        assertContentEquals(before.payload.copyForCodec(), f.record().payload.copyForCodec())
        assertEquals(1, f.ids); assertEquals(0, f.sends)
    } }

    @Test fun failedActualJournalReadClearsFormatUntilAnotherSuccessfulRead() = runTest { fixture { f ->
        val experience = f.reviewed(); val drafts = experience.postDrafts!!
        value(drafts.newLocalDraft("Created through actual controller"))
        val before = f.record()
        assertEquals(PostDraftJournalFormat.CURRENT, drafts.states.value.journalFormat)
        // Negative storage-fixture fault only: replace the version tag of an actual
        // controller-created row. This does not seed a valid journal or grant authority.
        val corruptRevision = value(f.session.access.store.commit(SCOPE,
            listOf(StoreMutation.Put(f.key, before.revision, 99, before.payload)))).getValue(f.key)!!
        val failed = drafts.restoreLocal()
        assertIs<PortResult.Failure>(failed)
        assertEquals(PostDraftJournalFormat.NOT_OBSERVED, drafts.states.value.journalFormat)
        value(drafts.back())
        assertEquals(PostDraftJournalFormat.NOT_OBSERVED, drafts.states.value.journalFormat)
        assertEquals(corruptRevision, f.record().revision)
        value(f.session.access.store.commit(SCOPE,
            listOf(StoreMutation.Put(f.key, corruptRevision, before.schemaVersion, before.payload))))
        value(drafts.restoreLocal())
        assertEquals(PostDraftJournalFormat.CURRENT, drafts.states.value.journalFormat)
        assertEquals("Created through actual controller", drafts.states.value.localDrafts.single().caption)
        assertEquals(1, f.ids); assertEquals(0, f.sends)
    } }

    @Test fun pendingHistoryReadKeepsActualOriginalRetryBehindTheLoadingFrame() = runTest { fixture { f ->
        val entry = f.offlineOriginal()
        val original = value(entry.publications.prepareOriginalRetry()).retry!!.original
        value(entry.publications.dismissReview())
        val selected = entry.drafts.states.value.selected!!
        value(entry.drafts.editCaption(selected.clientDraftId, "Newer changes kept separately"))
        assertTrue(entry.drafts.states.value.selected!!.localAcknowledged)
        val before = f.record(); val writes = f.session.localWriteStatements; val ids = f.ids
        val owner = ReviewedPublicationHistoryRead(); val failures = mutableListOf<FailureReason>()
        val gate = CompletableDeferred<Unit>(); f.source.gate = gate
        val ticket = owner.begin { entry.drafts.states.value.phase != PostDraftPhase.UNAVAILABLE }
        val resolves = f.source.resolves
        val opening = async { owner.restore(entry.publications, ticket, failures::add) }
        runCurrent(); assertTrue(owner.pending); assertTrue(f.source.resolves > resolves)
        // This is the actual wrapper branch: the actionable composer is not mounted while
        // pending. The controller call is deliberately real, but must remain unreachable.
        var childActions = 0
        if (!owner.pending) { childActions++; value(entry.publications.prepareOriginalRetry()) }
        assertEquals(0, childActions); assertNull(entry.publications.states.value.retry)
        f.source.gate = null; gate.complete(Unit); opening.await()
        assertFalse(owner.pending); assertTrue(failures.isEmpty())
        val retry = value(entry.publications.prepareOriginalRetry()).retry!!
        assertEquals(original.commandId, retry.original.commandId)
        assertContentEquals(original.exactOriginalPostWrite.encodeUtf8(), retry.original.exactOriginalPostWrite.encodeUtf8())
        assertEquals("Newer changes kept separately", entry.drafts.states.value.selected!!.caption)
        assertTrue(retry.separatelyObservedCurrentLocal.exactHistoricalSnapshot.encodeUtf8().decodeToString()
            .contains("Newer changes kept separately"))
        assertTrue(retry.isCurrentForNavigation); assertEquals(0, retry.observedAttempts)
        assertFalse(entry.publications.states.value.acknowledged)
        assertEquals(before.revision, f.record().revision); assertContentEquals(before.payload.copyForCodec(), f.record().payload.copyForCodec())
        assertEquals(writes, f.session.localWriteStatements); assertEquals(ids, f.ids); assertEquals(0, f.sends)
    } }

    @Test fun pendingHistoryReadKeepsActualUnsentCancellationBehindTheLoadingFrame() = runTest { fixture { f ->
        val entry = f.offlineOriginal()
        val original = value(entry.publications.prepareOriginalRetry()).retry!!.original
        value(entry.publications.dismissReview())
        val before = f.record(); val writes = f.session.localWriteStatements; val ids = f.ids
        val owner = ReviewedPublicationHistoryRead(); val failures = mutableListOf<FailureReason>()
        val gate = CompletableDeferred<Unit>(); f.source.gate = gate
        val ticket = owner.begin { entry.drafts.states.value.phase != PostDraftPhase.UNAVAILABLE }
        val opening = async { owner.restore(entry.publications, ticket, failures::add) }
        runCurrent(); assertTrue(owner.pending)
        var childActions = 0
        if (!owner.pending) { childActions++; value(entry.publications.prepareUnsentCancellation()) }
        assertEquals(0, childActions); assertNull(entry.publications.states.value.unsentCancellation)
        f.source.gate = null; gate.complete(Unit); opening.await()
        assertFalse(owner.pending); assertTrue(failures.isEmpty())
        val cancellation = value(entry.publications.prepareUnsentCancellation()).unsentCancellation!!
        assertEquals(original.commandId, cancellation.original.commandId)
        assertContentEquals(original.exactOriginalPostWrite.encodeUtf8(), cancellation.original.exactOriginalPostWrite.encodeUtf8())
        assertTrue(cancellation.isCurrentForNavigation); assertFalse(entry.publications.states.value.acknowledged)
        assertEquals(before.revision, f.record().revision); assertContentEquals(before.payload.copyForCodec(), f.record().payload.copyForCodec())
        assertEquals(writes, f.session.localWriteStatements); assertEquals(ids, f.ids); assertEquals(0, f.sends)
    } }

    @Test fun ungatedActualChildPreparationSupersedesTheHeldHistoryRead() = runTest {
        for (cancel in listOf(false, true)) fixture { f ->
            val entry = f.offlineOriginal()
            val before = f.record(); val writes = f.session.localWriteStatements; val ids = f.ids
            val gate = CompletableDeferred<Unit>(); f.source.gate = gate
            val opening = async { entry.publications.restore() }
            runCurrent()
            // Control reproduces the old UI interleaving: the real replacing preparation
            // rotates the controller generation before the older read releases its mutex.
            val replacing = async { if (cancel) entry.publications.prepareUnsentCancellation() else entry.publications.prepareOriginalRetry() }
            runCurrent(); f.source.gate = null; gate.complete(Unit)
            assertIs<PortResult.Failure>(opening.await())
            val fresh = value(replacing.await())
            if (cancel) assertTrue(fresh.unsentCancellation!!.isCurrentForNavigation)
            else assertTrue(fresh.retry!!.isCurrentForNavigation)
            assertFalse(fresh.acknowledged)
            assertEquals(before.revision, f.record().revision); assertContentEquals(before.payload.copyForCodec(), f.record().payload.copyForCodec())
            assertEquals(writes, f.session.localWriteStatements); assertEquals(ids, f.ids); assertEquals(0, f.sends)
        }
    }

    @Test fun backRetiresHeldHistoryReadAndItsFinallyCannotUnlockANewerRead() = runTest { fixture { f ->
        val entry = f.offlineOriginal(); val before = f.record(); val ids = f.ids
        val owner = ReviewedPublicationHistoryRead(); val failures = mutableListOf<FailureReason>()
        var navigation: Any = Any(); val oldNavigation = navigation
        val oldGate = CompletableDeferred<Unit>(); f.source.gate = oldGate
        val oldTicket = owner.begin { navigation === oldNavigation && entry.drafts.states.value.phase != PostDraftPhase.UNAVAILABLE }
        val oldRead = async { owner.restore(entry.publications, oldTicket, failures::add) }
        runCurrent(); assertTrue(owner.pending)
        owner.retire(); navigation = Any() // Same synchronous wrapper Back/exit fence.
        assertFalse(owner.pending); value(entry.drafts.back())
        val newGate = CompletableDeferred<Unit>(); f.source.gate = newGate
        val newNavigation = navigation
        val newTicket = owner.begin { navigation === newNavigation && entry.drafts.states.value.phase != PostDraftPhase.UNAVAILABLE }
        val newRead = async { owner.restore(entry.publications, newTicket, failures::add) }
        runCurrent(); assertTrue(owner.pending)
        oldGate.complete(Unit); oldRead.await(); runCurrent()
        assertTrue(owner.pending); assertTrue(failures.isEmpty())
        f.source.gate = null; newGate.complete(Unit); newRead.await()
        assertFalse(owner.pending); assertTrue(failures.isEmpty())
        assertEquals(PostDraftScreen.LOCAL_LIST, entry.drafts.states.value.screen)
        assertNull(entry.drafts.states.value.selected)
        assertEquals(0, entry.publications.states.value.pending!!.observedAttempts)
        assertFalse(entry.publications.states.value.acknowledged)
        assertEquals(before.revision, f.record().revision); assertContentEquals(before.payload.copyForCodec(), f.record().payload.copyForCodec())
        assertEquals(ids, f.ids); assertEquals(0, f.sends)
    } }

    @Test fun actualSessionInvalidationMakesHeldHistoryReadUnableToReportOrReopen() = runTest { fixture { f ->
        val entry = f.offlineOriginal(); val before = f.record(); val ids = f.ids
        val owner = ReviewedPublicationHistoryRead(); val failures = mutableListOf<FailureReason>()
        val gate = CompletableDeferred<Unit>(); f.source.gate = gate
        val ticket = owner.begin { entry.drafts.states.value.phase != PostDraftPhase.UNAVAILABLE }
        val opening = async { owner.restore(entry.publications, ticket, failures::add) }
        runCurrent(); assertTrue(owner.pending)
        val oldLease = f.session.access.lease
        f.source.revoke(); f.session.reopen()
        assertNotSame(oldLease, f.session.access.lease); assertTrue(owner.pending)
        // Actual unavailable state redacts the outer wrapper immediately; an invalid view
        // does not pretend that the still-running read has released its controller flight.
        assertEquals(PostDraftPhase.UNAVAILABLE, entry.drafts.states.value.phase)
        gate.complete(Unit); opening.await()
        assertTrue(failures.isEmpty()); assertFalse(owner.pending)
        assertEquals(PostDraftPhase.UNAVAILABLE, entry.drafts.states.value.phase)
        assertEquals(PostComposerPhase.UNAVAILABLE, entry.publications.states.value.phase)
        assertEquals(before.revision, f.record().revision); assertContentEquals(before.payload.copyForCodec(), f.record().payload.copyForCodec())
        assertEquals(ids, f.ids); assertEquals(0, f.sends)
    } }

    @Test fun changedActualDraftViewDoesNotMountComposerBeforeTheReadSettles() = runTest { fixture { f ->
        val entry = f.offlineOriginal(); val before = f.record(); val ids = f.ids
        val initial = entry.drafts.states.value
        val pin = ReviewedDraftViewPin(initial.screen, initial.selected?.clientDraftId, initial.selected?.localRevision)
        val owner = ReviewedPublicationHistoryRead(); val failures = mutableListOf<FailureReason>()
        val gate = CompletableDeferred<Unit>(); f.source.gate = gate
        val ticket = owner.begin {
            entry.drafts.states.value.let { pin.matches(it.screen, it.selected?.clientDraftId,
                it.selected?.localRevision, it.phase != PostDraftPhase.UNAVAILABLE) }
        }
        val opening = async { owner.restore(entry.publications, ticket, failures::add) }
        runCurrent(); assertTrue(owner.pending)
        // An external actual draft-view change is not the wrapper's explicit retire action.
        value(entry.drafts.back()); assertEquals(PostDraftScreen.LOCAL_LIST, entry.drafts.states.value.screen)
        assertNull(entry.drafts.states.value.selected)
        assertTrue(owner.pending)
        var childActions = 0
        if (!owner.pending) { childActions++; value(entry.publications.prepareOriginalRetry()) }
        assertEquals(0, childActions)
        f.source.gate = null; gate.complete(Unit); opening.await()
        assertFalse(owner.pending); assertTrue(failures.isEmpty())
        assertEquals(before.revision, f.record().revision); assertContentEquals(before.payload.copyForCodec(), f.record().payload.copyForCodec())
        assertEquals(ids, f.ids); assertEquals(0, f.sends)
    } }

    @Test fun refusedLegacyHistoryReadSettlesWithoutErasingTheReadOnlyDraft() = runTest { fixture { f ->
        val legacy = f.legacy(withDrafts = true)
        val local = value(legacy.postDrafts!!.newLocalDraft("Legacy retained verbatim")).selected!!
        val before = f.record(); value(legacy.close()); f.experiences.remove(legacy)
        val experience = f.reviewed(); assertIs<PortResult.Failure>(experience.openPostDrafts())
        val entry = experience.reviewedPosts!!; value(entry.drafts.openLocal(local.clientDraftId))
        val owner = ReviewedPublicationHistoryRead(); val failures = mutableListOf<FailureReason>()
        val ticket = owner.begin { entry.drafts.states.value.phase != PostDraftPhase.UNAVAILABLE }
        assertTrue(owner.pending)
        owner.restore(entry.publications, ticket, failures::add)
        assertFalse(owner.pending); assertEquals(listOf(FailureReason.NOT_CONFIGURED), failures)
        // Settled failure does not prevent mounting the cached composer in read-only mode.
        assertFalse(reviewedDraftMutationControlsAllowed(true, entry.drafts.states.value.journalFormat))
        assertEquals(PostDraftJournalFormat.LEGACY, entry.drafts.states.value.journalFormat)
        assertEquals(local.caption, entry.drafts.states.value.selected!!.caption)
        assertEquals(before.revision, f.record().revision); assertContentEquals(before.payload.copyForCodec(), f.record().payload.copyForCodec())
        assertEquals(1, f.ids); assertEquals(0, f.sends)
    } }

    private suspend fun TestScope.fixture(block: suspend (Fixture) -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = PostDraftHttpSessionFixture.open(dispatcher,
            StoredCredentials.Account(SCOPE, SecretText("synthetic-experience-token"), null, Long.MAX_VALUE, SecretText(DEVICE)))
        val fixture = Fixture(session, dispatcher)
        try { withContext(dispatcher) { block(fixture) }; session.requireNoNativeWork() }
        finally {
            withContext(NonCancellable + dispatcher) {
                fixture.sources.forEach { it.releaseGates() }
                fixture.experiences.forEach { value(it.close()) }
                session.close()
            }
        }
    }
    private class Fixture(val session: PostDraftHttpSessionFixture, val dispatcher: CoroutineDispatcher) {
        var builders = 0; var ids = 0; var sends = 0
        var online = true
        val experiences = mutableListOf<MealFlowExperience>()
        val sources = mutableListOf<Source>()
        lateinit var source: Source; lateinit var integration: Integration
        val transport = object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                assertSame(session.access.lease, lease); sends++
                return PortResult.Failure(FailureReason.NOT_CONFIGURED)
            }
        }
        fun integrationFor(access: AuthenticatedMealPlanningAccess): Integration {
            source = Source(access, session.boundary).also { sources += it }
            integration = Integration(source)
            return integration
        }
        fun reviewed(builder: (AuthenticatedMealPlanningAccess) -> ReviewedKitchenIntegration = ::integrationFor): MealFlowExperience =
            MealFlowExperience.fromSessionWithReviewedPosts(session.access, transport, session.boundary, dispatcher,
                EpochClock { NOW }, ConnectivityPort { if (online) Connectivity.ONLINE else Connectivity.OFFLINE }, MealOperationIds { nextId() },
                MealFlowPolicy(86_400_000, 60_000, 4, 32), IngredientPickerPolicy(2, 2, 20, 86_400_000),
                MealInputChoices(emptyList(), emptyList()), KitchenInputPolicy(2, 2, 20, 8, 65_536),
                CookingFlowPolicy(60_000, 65_536, 65_536), CookbookPolicy(2, 60_000), DRAFT_POLICY, PUBLICATION_POLICY,
                integrationForAccess = { actual -> builders++; builder(actual) }).also { experiences += it }
        private fun nextId(): String { ids++; return "dddddddd-1000-4000-8000-" + (ids + 1).toString().padStart(12, '0') }
        suspend fun offlineOriginal(): ReviewedPostEntry {
            val experience = reviewed(); value(experience.openPostDrafts())
            val entry = experience.reviewedPosts!!
            val first = value(entry.drafts.newLocalDraft(ORIGINAL)).selected!!
            val disclosure = value(entry.loadDisclosure(first.clientDraftId, ReviewedPostPurpose.PUBLICATION))
            val kept = value(entry.editChoices(first.clientDraftId, first.localRevision,
                ReviewedPostChoices(ORIGINAL, OptionalValue.Absent, emptyList(), PublicationAudience.OnlyYou,
                    false, OptionalValue.Absent, false, disclosure, OptionalValue.Absent))).selected!!
            integration.allowDirectPublication = true
            val review = value(entry.prepareSelectedPublication(kept.clientDraftId, kept.localRevision, ReviewedPostBranch.DIRECT_LOCAL))
            online = false
            assertEquals(FailureReason.OFFLINE, assertIs<PortResult.Failure>(entry.publications.confirmPublish(review.token)).reason)
            val state = value(entry.publications.restore())
            assertEquals(0, state.pending!!.observedAttempts); assertEquals("AWAITING_CONFIRMATION", state.pending!!.phase)
            assertFalse(state.acknowledged); assertEquals(2, ids); assertEquals(0, sends)
            return entry
        }
        fun legacy(withDrafts: Boolean): MealFlowExperience =
            MealFlowExperience.fromSession(session.access, transport, session.boundary, dispatcher,
                EpochClock { NOW }, ConnectivityPort { Connectivity.ONLINE }, MealOperationIds { ids++; ROOT },
                MealFlowPolicy(86_400_000, 60_000, 4, 32), IngredientPickerPolicy(2, 2, 20, 86_400_000),
                MealInputChoices(emptyList(), emptyList()), KitchenInputPolicy(2, 2, 20, 8, 65_536),
                CookingFlowPolicy(60_000, 65_536, 65_536), CookbookPolicy(2, 60_000),
                if (withDrafts) DRAFT_POLICY else null).also { experiences += it }
        val key get() = RecordKey("mealflow.post-drafts.v1", session.access.originBinding.lowercase())
        suspend fun record() = assertNotNull(value(session.access.store.read(SCOPE, key)))
    }
    private class Source(val actual: AuthenticatedMealPlanningAccess, val boundary: SessionBoundary) : PostPublicationPrincipalIntegration() {
        val owner = Any(); private var generation = Any(); private var available = true
        var resolves = 0; var refuse = false
        private val heldGates = mutableSetOf<CompletableDeferred<Unit>>()
        var gate: CompletableDeferred<Unit>? = null
            set(value) { field = value; value?.let { heldGates += it } }
        fun releaseGates() { gate = null; heldGates.forEach { it.complete(Unit) } }
        val bindings = mutableListOf<PublicationSessionBinding>()
        val delivery = Delivery(this)
        override suspend fun resolve(binding: PublicationSessionBinding): PortResult<PublicationPrincipalSnapshot> {
            resolves++
            if (!matchesSession(binding, actual, boundary) || !available) return PortResult.Failure(FailureReason.STALE_SESSION)
            if (refuse) return PortResult.Failure(FailureReason.NOT_CONFIGURED)
            if (bindings.none { it === binding }) bindings += binding
            val result = mappedPrincipal(binding, ACCOUNT, owner, generation)
            // A deliberately late provider callback cannot defeat controller cancellation.
            gate?.let { withContext(NonCancellable) { it.await() } }
            return PortResult.Value(result)
        }
        override suspend fun requireCurrent(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot) =
            if (isCurrent(binding, principal)) PortResult.Value(Unit) else PortResult.Failure(FailureReason.STALE_SESSION)
        override fun isCurrent(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot) =
            available && matchesSession(binding, actual, boundary) && matchesCurrentPrincipal(binding, principal, owner, generation)
        fun requireContext(context: ReviewedPostPrerequisiteContext) {
            val binding = assertNotNull(bindings.singleOrNull { isCurrent(it, context.principal) })
            assertSame(binding.lease, context.lease); assertSame(boundary, context.boundary)
            assertEquals(binding.origin, context.origin); assertEquals(binding.environment, context.environment)
            assertEquals(ACCOUNT, context.principal.canonicalUserId)
        }
        fun revoke() { delivery.revoke(); available = false; generation = Any() }
    }
    private class Delivery(private val source: Source) : PostPublicationDeliveryIntegration(source, maxPendingDeliveries = 4) {
        fun revoke() = revokeCurrent()
        override fun capture(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot) =
            if (source.isCurrent(binding, principal)) PortResult.Value(witness(binding, principal, generationFor(principal)))
            else PortResult.Failure(FailureReason.STALE_SESSION)
    }
    private class Integration(private val source: Source) : ReviewedKitchenIntegration {
        override val principals get() = source
        override val delivery get() = source.delivery
        var disclosures = 0; val purposes = mutableListOf<ReviewedPostPurpose>()
        var allowDirectPublication = false
        override suspend fun disclosure(context: ReviewedPostPrerequisiteContext): PortResult<PublicationDisclosure> {
            source.requireContext(context); disclosures++; purposes += context.purpose
            return PortResult.Value(DISCLOSURE)
        }
        override suspend fun requireNewPrivateSave(context: ReviewedPostPrerequisiteContext, check: ReviewedPrivateSaveCheck): PortResult<Unit> =
            PortResult.Failure(FailureReason.NOT_CONFIGURED)
        override suspend fun requirePrivateSaveReplay(context: ReviewedPostPrerequisiteContext, check: ReviewedPrivateSaveReplayCheck): PortResult<Unit> =
            PortResult.Failure(FailureReason.NOT_CONFIGURED)
        override suspend fun requireNewPublication(context: ReviewedPostPrerequisiteContext, check: ReviewedPublicationCheck): PortResult<Unit> {
            if (!allowDirectPublication) return PortResult.Failure(FailureReason.NOT_CONFIGURED)
            source.requireContext(context)
            assertEquals(ReviewedPostBranch.DIRECT_LOCAL, check.branch)
            assertNull(check.savedDraftId); assertNull(check.exactSavedBaseline)
            assertEquals(DISCLOSURE.version, check.displayedDisclosure.version)
            assertEquals(DISCLOSURE.text, check.displayedDisclosure.text)
            val body = Json.parseToJsonElement(check.exactPostWrite.encodeUtf8().decodeToString()).jsonObject
            assertEquals(ORIGINAL, body.getValue("caption").jsonPrimitive.content)
            assertEquals("self", body.getValue("audience").jsonObject.getValue("kind").jsonPrimitive.content)
            assertTrue(body.getValue("audience").jsonObject.getValue("circleIds").jsonArray.isEmpty())
            assertTrue(body.getValue("mediaIds").jsonArray.isEmpty())
            assertEquals(false, body.getValue("keepOnPlate").jsonPrimitive.boolean)
            assertEquals(false, body.getValue("allowRecipeSaves").jsonPrimitive.boolean)
            assertFalse("attachment" in body); assertFalse("sourcePostId" in body)
            return PortResult.Value(Unit)
        }
        override suspend fun requirePublicationReplay(context: ReviewedPostPrerequisiteContext, check: ReviewedPublicationReplayCheck): PortResult<Unit> =
            PortResult.Failure(FailureReason.NOT_CONFIGURED)
    }
    private companion object {
        val SCOPE = StorageScope("test", ActorKind.ACCOUNT, "opaque-experience-owner")
        const val DEVICE = "dddddddd-1000-4000-8000-000000000001"
        const val ROOT = "dddddddd-1000-4000-8000-000000000002"
        const val ACCOUNT = "dddddddd-1000-4000-8000-000000000003"
        const val NOW = 1_789_387_200_000L
        const val ORIGINAL = "Original held while history opens"
        val DRAFT_POLICY = PostDraftClientPolicy(8, 1_048_576, 65_536, 64, 64, 2, 100, 60_000)
        val PUBLICATION_POLICY = PostPublicationClientPolicy(1_048_576, 65_536, 8192, 8, 32, 8, 8, 8, 8, 65_536, 4096, 60_000)
        val DISCLOSURE = PublicationDisclosure("synthetic-experience-v1", "Synthetic self-only test disclosure. No live account or rights approval.")
        fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
    }
}
