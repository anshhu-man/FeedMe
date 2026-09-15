package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.document
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.number
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Actual sole owner/composition and synthetic CAS store. Publication replies here are clearly
 * canonical synthetic data, not actual HTTP/PG/queue receipt or caller-delivery proof. */
@OptIn(ExperimentalCoroutinesApi::class)
class PostDraftCurrentJournalOwnerTest {
    @Test fun configurationRegistrationAndPureCurrentPreflightHaveNoIoClaimOrNotification() = runTest {
        val f = Harness(this)
        try {
            f.journal.registerDraftPublicationObserver(f.draft.participant) { f.notifications += it }
            f.journal.preflightCurrentData(f.row())
            assertSame(f.publicationPolicy, f.journal.configuredPublicationPolicy)
            f.noIo(); assertTrue(f.notifications.isEmpty())
            val other = PostDraftJournalOwner(f.composition, f.policy, f.publicationPolicy)
            val participant = other.register(f.draft.borrower)
            f.composition.operate(f.draft.borrower) {
                val use = other.enter(f.composition.composerPermit(f.draft.borrower), participant)
                other.leave(use)
            }
            other.release(participant); f.noIo()
        } finally { f.close() }
    }

    @Test fun configuredMissingRowIsCurrentOldFactoryMissingRowIsLegacyAndNeitherWrites() = runTest {
        val current = Harness(this); val legacy = Harness(this, configured = false)
        try {
            current.drafts { use -> assertIs<PostDraftJournalEntry.Current>(current.journal.readDrafts(use)) }
            legacy.drafts { use -> assertIs<PostDraftJournalEntry.Legacy>(legacy.journal.readDrafts(use)) }
            assertEquals(0, current.shared.store.writes); assertEquals(0, legacy.shared.store.writes)
            assertTrue(current.shared.store.records.isEmpty()); assertTrue(legacy.shared.store.records.isEmpty())
        } finally { current.close(); legacy.close() }
    }

    @Test fun existingLegacyBytesStayLegacyEvenWithConfigurationAndNoReadOrBackMigration() = runTest {
        val f = Harness(this)
        try {
            val old = PostDraftCodec(ORIGIN, f.policy).encode(PostRecord(NOW, listOf(PostLocal(CLIENT, 1, "Legacy", "")), listOf(CLIENT)))
            f.shared.store.records[KEY] = PrivateRecord(1, 1, old)
            f.drafts { use ->
                assertIs<PostDraftJournalEntry.Legacy>(f.journal.readDrafts(use))
                failure(FailureReason.NOT_CONFIGURED) { f.journal.readCurrent(use) }
            }
            f.publications { use -> failure(FailureReason.NOT_CONFIGURED) { f.journal.readForPublication(use, CLIENT, 1) } }
            assertContentEquals(old.copyForCodec(), f.shared.store.records.getValue(KEY).payload.copyForCodec())
            assertEquals(1, f.shared.store.records.getValue(KEY).schemaVersion); assertEquals(0, f.shared.store.writes)
        } finally { f.close() }
    }

    @Test fun oldFactoryRestoringCurrentFormatFailsNotConfiguredWithExactBytesUntouched() = runTest {
        val f = Harness(this, configured = false)
        try {
            f.seed()
            val before = f.shared.store.records.getValue(KEY)
            f.drafts { use -> failure(FailureReason.NOT_CONFIGURED) { f.journal.readDrafts(use) } }
            assertEquals(0, f.shared.store.writes); assertContentEquals(before.payload.copyForCodec(), f.shared.store.records.getValue(KEY).payload.copyForCodec())
        } finally { f.close() }
    }

    @Test fun actualCurrentMutationUsesOnlyOriginalNamespaceAndPreparedEntryIdentity() = runTest {
        val f = Harness(this)
        try {
            f.drafts { use ->
                val entry = f.journal.readCurrent(use)
                failure(FailureReason.CONFLICT) { f.journal.mutationCurrent(use, PostDraftV2Entry(entry.record, entry.value), f.row()) }
                val change = f.journal.mutationCurrent(use, entry, f.row())
                failure(FailureReason.CONFLICT) { f.journal.beforeCurrentCommit(use, listOf(StoreMutation.Put(change.key, change.expectedRevision, 2, change.payload))) }
                f.journal.commitCurrent(use, change)
                val read = f.journal.readCurrent(use)
                assertEquals(CLIENT, read.value.locals.single().clientDraftId); assertEquals(2, read.record!!.schemaVersion)
            }
            assertEquals(setOf(KEY), f.shared.store.records.keys); assertEquals(1, f.shared.store.writes)
            assertEquals(0, f.shared.store.erases); assertEquals(0, f.shared.calls)
        } finally { f.close() }
    }

    @Test fun currentAndLegacyBeforeCommitHooksCannotLaunderEachOthersPreparedSchema() = runTest {
        val f = Harness(this)
        try {
            f.drafts { use ->
                val current = f.journal.mutationCurrent(use, f.journal.readCurrent(use), f.row())
                failure(FailureReason.CONFLICT) { f.journal.beforeLegacyCommit(use, listOf(current)) }
                val legacy = f.journal.mutationLegacy(use, PostEntry(null, PostRecord(NOW)), PostRecord(NOW))
                failure(FailureReason.CONFLICT) { f.journal.beforeCurrentCommit(use, listOf(legacy)) }
            }
            assertEquals(0, f.shared.store.writes)
        } finally { f.close() }
    }

    @Test fun siblingAndStaleUsesCannotReadCurrentOrExtractPublicationMutations() = runTest {
        val f = Harness(this); val foreign = Harness(this)
        lateinit var old: DraftJournalUse
        try {
            f.seed()
            f.publications { use ->
                failure(FailureReason.NOT_CONFIGURED) { f.journal.readCurrent(use) }
                failure(FailureReason.NOT_CONFIGURED) { f.journal.preflightCurrent(use, f.row()) }
            }
            f.drafts { use -> old = use; failure(FailureReason.NOT_CONFIGURED) { f.journal.requirePublicationState(use) } }
            f.drafts { failure(FailureReason.STALE_SESSION) { f.journal.readCurrent(old) } }
            val (_, contribution) = f.hold()
            f.publications { use -> failure(FailureReason.STALE_SESSION) { f.journal.mutations(use, contribution) } }
            foreign.publications { use -> failure(FailureReason.CONFLICT) { foreign.journal.observePublicationReadback(use, contribution) } }
        } finally { f.close(); foreign.close() }
    }

    @Test fun publicationPinRejectsWrongRevisionReplacedPinHeldProposalAndFreshStoredEdit() = runTest {
        val f = Harness(this)
        try {
            f.seed()
            lateinit var old: DraftReviewPin
            f.publications { use ->
                failure(FailureReason.CONFLICT) { f.journal.readForPublication(use, CLIENT, 2) }
                old = f.journal.readForPublication(use, CLIENT, 1)
                f.journal.readForPublication(use, CLIENT, 1)
                failure(FailureReason.CONFLICT) { f.journal.requirePin(use, old) }
                old = f.journal.readForPublication(use, CLIENT, 1)
            }
            val held = PostDraftCurrentEdit(f.snapshots.withText(f.snapshot(), "Uncommitted", null, 2), listOf(f.snapshot()))
            PostDraftCurrentHeld.retainEdit(f.access, f.shared.boundary, held)
            f.publications { use -> failure(FailureReason.CONFLICT) { f.journal.requirePin(use, old) } }
            PostDraftCurrentHeld.clearEdit(f.access, f.shared.boundary, held)
            f.edit(CLIENT, "Actually stored")
            f.publications { use -> failure(FailureReason.CONFLICT) { f.journal.requirePin(use, old) } }
        } finally { f.close() }
    }

    @Test fun probePreflightReturnsActualProposedProjectionWithoutRetainingEvidenceOrAllocatingIds() = runTest {
        val f = Harness(this)
        try {
            f.seed(); val writes = f.shared.store.writes
            f.publications { use ->
                val pin = f.journal.readForPublication(use, CLIENT, 1)
                val snapshot = f.journal.reviewSnapshot(use, pin)
                val probe = f.link(snapshot, number(90)); val probeJournal = f.pending(probe)
                val projection = f.journal.preflightHold(use, pin, probe, f.publicationCodec.reservedPublishedBytes(probeJournal))
                f.cross.requireConsistent(probeJournal, projection)
                assertEquals(writes, f.shared.store.writes); assertTrue(f.notifications.isEmpty())
                val real = f.link(snapshot)
                val contribution = f.journal.prepareHold(use, pin, real, f.publicationCodec.reservedPublishedBytes(f.pending(real)))
                assertEquals(COMMAND, f.journal.publicationProjection(use, contribution).hold!!.original.commandId)
                assertFalse(number(90) in f.current().issued)
                failure(FailureReason.CONFLICT) { f.journal.observePublicationReadback(use, contribution) }
            }
            assertEquals(0, f.shared.calls)
        } finally { f.close() }
    }

    @Test fun preparedHoldRequiresExactBatchObjectAndActualChangedReadbackNotConstructedAcknowledgement() = runTest {
        val f = Harness(this)
        try {
            f.seed()
            f.publications { use ->
                val pin = f.journal.readForPublication(use, CLIENT, 1); val link = f.link(f.journal.reviewSnapshot(use, pin))
                val contribution = f.journal.prepareHold(use, pin, link, f.publicationCodec.reservedPublishedBytes(f.pending(link)))
                val actual = f.journal.mutations(use, contribution).single() as StoreMutation.Put
                failure(FailureReason.CONFLICT) { f.journal.beforePublicationCommit(use, contribution, listOf(StoreMutation.Put(KEY, actual.expectedRevision, 2, actual.payload))) }
                failure(FailureReason.STORAGE_FAILURE) { f.journal.verifyPublicationReadback(use, contribution, mapOf(KEY to actual.expectedRevision!! + 1)) }
                f.activeContribution = contribution
                val acknowledged = value(f.composition.store.commit(f.shared.scope, listOf(actual)))
                f.journal.verifyPublicationReadback(use, contribution, acknowledged)
                assertEquals(COMMAND, f.current().publicationHold!!.link.commandId)
            }
        } finally { f.close() }
    }

    @Test fun admittedCurrentEditsRecomputeHoldReserveAndCannotAlterPublicationMaterialOrRecycleIds() = runTest {
        val f = Harness(this)
        try {
            f.seed(); f.hold()
            val oldReserve = f.current().publicationHold!!.reservation.draftFinalizationReservedBytes
            f.edit(CLIENT, "Much longer actual local text")
            assertTrue(f.current().publicationHold!!.reservation.draftFinalizationReservedBytes > oldReserve)
            f.drafts { use ->
                val entry = f.journal.readCurrent(use)
                failure(FailureReason.CONFLICT) { f.journal.mutationCurrent(use, entry, entry.value.copy(publicationHold = null)) }
                failure(FailureReason.INVALID_DATA) { f.journal.mutationCurrent(use, entry, entry.value.copy(issued = entry.value.issued.drop(1))) }
                failure(FailureReason.CONFLICT) { f.journal.mutationCurrent(use, entry, entry.value.copy(locals = listOf(f.snapshot()))) }
            }
            assertEquals(COMMAND, f.current().publicationHold!!.link.commandId)
        } finally { f.close() }
    }

    @Test fun terminalRejectsUnpersistedHeldLocalIntentBeforeNotificationAndKeepsReceiptDataUntouched() = runTest {
        val f = Harness(this)
        try {
            f.journal.registerDraftPublicationObserver(f.draft.participant) { f.notifications += it }
            f.seed(); val (link, _) = f.hold()
            val held = PostDraftCurrentEdit(f.snapshots.withText(f.snapshot(), "Must not vanish", "", 2), listOf(f.snapshot()))
            PostDraftCurrentHeld.retainEdit(f.access, f.shared.boundary, held)
            val receipt = f.receipt(); val exact = receipt.document.encodeUtf8(); val writes = f.shared.store.writes
            f.publications { use -> failure(FailureReason.CONFLICT) { f.journal.prepareTerminal(use, link, receipt) } }
            assertSame(held, PostDraftCurrentHeld.edit(f.access, f.shared.boundary)); assertTrue(f.notifications.isEmpty())
            assertEquals(writes, f.shared.store.writes); assertContentEquals(exact, receipt.document.encodeUtf8())
            assertNotNull(f.current().publicationHold)
        } finally { f.close() }
    }

    @Test fun actualTerminalRetainsCompleteNewerRemainderAndNotifiesOnlyItsRootWithoutAcknowledgingUi() = runTest {
        val f = Harness(this)
        try {
            f.journal.registerDraftPublicationObserver(f.draft.participant) { f.notifications += it }
            f.seed(withOther = true); val (link, _) = f.hold(); f.edit(CLIENT, "Full newest content", "")
            val otherHeld = PostDraftCurrentEdit(f.snapshots.withText(f.snapshot(OTHER), "Other pending", null, 2), listOf(f.snapshot(OTHER)))
            PostDraftCurrentHeld.retainEdit(f.access, f.shared.boundary, otherHeld)
            f.publications { use ->
                val before = f.shared.store.writes
                val terminal = f.journal.prepareTerminal(use, link, f.receipt())
                assertEquals(before, f.shared.store.writes); assertEquals(listOf(CLIENT), f.notifications)
                assertSame(otherHeld, PostDraftCurrentHeld.edit(f.access, f.shared.boundary))
                val projection = f.journal.publicationProjection(use, terminal)
                assertEquals(OTHER, projection.locals.single().clientDraftId)
                assertEquals("Full newest content", projection.remainders.single().content.caption)
                assertEquals("", projection.remainders.single().content.altText)
                f.commit(use, terminal)
            }
            assertNull(f.current().publicationHold); assertEquals(CLIENT, f.current().terminals.single().clientId)
            assertEquals(CLIENT, f.current().remainders.single().clientId)
        } finally { f.close() }
    }

    @Test fun terminalRetainsExactNewerComposerChoicesAfterIndependentPublishedObservation() = runTest {
        val f = Harness(this)
        try {
            f.seed(); val (link, _) = f.hold()
            val choices = WireDocument.parse(" \n" + buildJsonObject {
                put("caption", "Newer complete choices"); put("altText", "")
                put("mediaIds", JsonArray(listOf(JsonPrimitive(number(41)), JsonPrimitive(number(40)))))
                put("audience", buildJsonObject { put("kind", "circles"); put("circleIds", JsonArray(listOf(JsonPrimitive(number(43)), JsonPrimitive(number(42))))) })
                put("keepOnPlate", true); put("allowRecipeSaves", true); put("saveDisclosureVersion", "newer-version")
                put("sourcePostId", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee".uppercase())
                put("attachment", buildJsonObject {
                    put("recipeVersionId", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee".uppercase())
                    put("reviewStatus", "reviewed"); put("rightsBasis", "catalogRedistributable")
                    put("confirmedChanges", JsonArray(listOf(JsonPrimitive("Retained exact source"))))
                })
            }.toString() + "\n")
            val content = DraftLocalContentV1.ComposerV2(choices, "Newer disclosure text")
            val complete = f.snapshots.create(CLIENT, 2, content, DraftServerAssociationV1.NotObserved)
            f.drafts { use ->
                val entry = f.journal.readCurrent(use)
                f.journal.commitCurrent(use, f.journal.mutationCurrent(use, entry, entry.value.copy(locals = listOf(complete))))
            }
            val publishedObservation = document(PostDraftControllerTest.draft(CLIENT, "Caption").json().jsonObject +
                mapOf("status" to JsonPrimitive("published"), "publishedPostId" to JsonPrimitive(POST)))
            f.drafts { use ->
                val entry = f.journal.readCurrent(use)
                val observed = f.snapshots.withServerAssociation(entry.value.locals.single(), DraftServerAssociationV1.Observed(publishedObservation, "\"1\""))
                f.journal.commitCurrent(use, f.journal.mutationCurrent(use, entry, entry.value.copy(locals = listOf(observed))))
            }
            f.publications { use -> f.commit(use, f.journal.prepareTerminal(use, link, f.receipt())) }
            val remainder = f.current().remainders.single().unsubmittedSnapshot
            assertContentEquals(complete.exactUtf8.copyForCodec(), remainder.exactUtf8.copyForCodec())
            assertContentEquals(choices.encodeUtf8(), assertIs<DraftLocalContentV1.ComposerV2>(remainder.content).exactChoices.encodeUtf8())
            assertIs<DraftServerAssociationV1.NotObserved>(remainder.serverAssociation)
            assertTrue(f.current().locals.isEmpty())
            assertContentEquals(link.exactUtf8.copyForCodec(), (f.current().terminals.single() as PostDraftTerminalV2.Published).link.exactUtf8.copyForCodec())
        } finally { f.close() }
    }

    @Test fun terminalCanonicalDataMustMatchOriginalAuthorSelectionAndObservedPublishedId() = runTest {
        val f = Harness(this)
        try {
            f.seed(); val (link, _) = f.hold(); val receipt = f.receipt()
            val wrong = document(receipt.document.json().jsonObject + ("caption" to JsonPrimitive("Another post")))
            f.publications { use -> failure(FailureReason.INVALID_DATA) { f.journal.prepareTerminal(use, link, PostPublicationReceipt(wrong, "\"1\"")) } }
            val actual = receipt.document.json().jsonObject
            val wrongAuthor = document(actual + ("author" to JsonObject(actual.getValue("author").jsonObject + ("userId" to JsonPrimitive(number(96))))))
            f.publications { use -> failure(FailureReason.INVALID_DATA) { f.journal.prepareTerminal(use, link, PostPublicationReceipt(wrongAuthor, "\"1\"")) } }
            val observed = document(PostDraftControllerTest.draft(CLIENT, "Caption").json().jsonObject +
                mapOf("status" to JsonPrimitive("published"), "publishedPostId" to JsonPrimitive(number(95))))
            f.drafts { use ->
                val entry = f.journal.readCurrent(use); val prior = entry.value.locals.single()
                val next = f.snapshots.withServerAssociation(prior, DraftServerAssociationV1.Observed(observed, "\"1\""))
                f.journal.commitCurrent(use, f.journal.mutationCurrent(use, entry, entry.value.copy(locals = listOf(next))))
            }
            f.publications { use -> failure(FailureReason.CONFLICT) { f.journal.prepareTerminal(use, link, receipt) } }
            assertNotNull(f.current().publicationHold); assertTrue(f.notifications.isEmpty())
        } finally { f.close() }
    }

    @Test fun unknownTerminalCommitCanFinalizeAfterActualUnrelatedOwnerSuccessorWithoutDroppingEdit() = runTest {
        val f = Harness(this)
        try {
            f.seed(withOther = true); val (link, _) = f.hold()
            lateinit var terminal: DraftMutationContribution
            f.publications { use ->
                terminal = f.journal.prepareTerminal(use, link, f.receipt())
                f.shared.store.failAfter = { it.any { change -> change.key == KEY } }
                failure(FailureReason.OUTCOME_UNKNOWN) { f.commit(use, terminal) }
            }
            // readCurrent observes actual terminal bytes before this owner-produced successor.
            f.edit(OTHER, "Independent later edit")
            f.publications { use ->
                failure(FailureReason.STALE_SESSION) { f.journal.mutations(use, terminal) }
                val readback = f.journal.observePublicationReadback(use, terminal)
                val finalized = f.journal.preparePublicationFinalization(use, readback)
                assertEquals("Independent later edit", f.journal.publicationProjection(use, finalized).locals.single().content.caption)
                f.commit(use, finalized)
                val retirement = f.journal.preparePublicationEvidenceRetirement(use, finalized)
                f.journal.retirePublicationEvidence(use, retirement)
                failure(FailureReason.CONFLICT) { f.journal.observePublicationReadback(use, terminal) }
            }
            assertEquals("Independent later edit", f.current().locals.single().content.caption)
            assertEquals(POST, (f.current().terminals.single() as PostDraftTerminalV2.Published).postId)
        } finally { f.close() }
    }

    @Test fun matchingTerminalInUntrackedLaterRowCannotReconstructActualReadbackOrAcknowledgement() = runTest {
        val f = Harness(this)
        try {
            f.seed(withOther = true); val (link, _) = f.hold()
            lateinit var terminal: DraftMutationContribution
            f.publications { use ->
                terminal = f.journal.prepareTerminal(use, link, f.receipt())
                f.shared.store.failAfter = { true }
                failure(FailureReason.OUTCOME_UNKNOWN) { f.commit(use, terminal) }
            }
            val actual = f.shared.store.records.getValue(KEY)
            val row = f.codec.decode(2, actual.payload)
            val changed = row.copy(locals = row.locals.map { f.snapshots.withText(it, "Untracked replacement", null, it.localRevision + 1) })
            // Deliberately corrupt fixture outside the owner: same valid terminal/link is not proof.
            f.shared.store.records[KEY] = PrivateRecord(actual.revision + 1, 2, f.codec.encode(changed))
            f.publications { use -> failure(FailureReason.CONFLICT) { f.journal.observePublicationReadback(use, terminal) } }
            assertEquals("Untracked replacement", f.current().locals.single().content.caption)
        } finally { f.close() }
    }

    @Test fun preparedButNeverAdmittedContributionCannotBecomeProofFromMatchingFixtureBytes() = runTest {
        val f = Harness(this)
        try {
            f.seed()
            f.publications { use ->
                val pin = f.journal.readForPublication(use, CLIENT, 1); val link = f.link(f.journal.reviewSnapshot(use, pin))
                val c = f.journal.prepareHold(use, pin, link, f.publicationCodec.reservedPublishedBytes(f.pending(link)))
                val proposed = f.journal.mutations(use, c).single() as StoreMutation.Put
                f.shared.store.records[KEY] = PrivateRecord(proposed.expectedRevision!! + 1, 2, proposed.payload)
                failure(FailureReason.CONFLICT) { f.journal.observePublicationReadback(use, c) }
            }
            assertEquals(0, f.shared.store.writes)
        } finally { f.close() }
    }

    @Test fun unsentReleaseKeepsRootAndIssuedIdAndUsesFreshCurrentFinalizationNotOldWrite() = runTest {
        val f = Harness(this)
        try {
            f.seed(); val (link, _) = f.hold()
            lateinit var unsent: DraftMutationContribution
            f.publications { use -> unsent = f.journal.prepareUnsentRelease(use, link); f.commit(use, unsent) }
            f.publications { use ->
                val readback = f.journal.observePublicationReadback(use, unsent)
                f.commit(use, f.journal.preparePublicationFinalization(use, readback))
                failure(FailureReason.STALE_SESSION) { f.journal.mutations(use, unsent) }
            }
            assertNull(f.current().publicationHold); assertTrue(f.current().terminals.isEmpty())
            assertEquals(CLIENT, f.current().locals.single().clientDraftId); assertTrue(COMMAND in f.current().issued)
        } finally { f.close() }
    }

    @Test fun sameLeaseReplacementCanObserveGenuineEvidenceButNeverReuseOldContributionForWriting() = runTest {
        val shared = Shared(); val f = Harness(this, shared); val replacement = Harness(this, shared)
        try {
            f.seed(); val (_, hold) = f.hold(); f.detach()
            replacement.publications { use ->
                failure(FailureReason.STALE_SESSION) { replacement.journal.mutations(use, hold) }
                val proof = replacement.journal.observePublicationReadback(use, hold)
                replacement.commit(use, replacement.journal.preparePublicationFinalization(use, proof))
            }
            assertTrue(shared.boundary.isCurrent(shared.lease)); assertEquals(COMMAND, replacement.current().publicationHold!!.link.commandId)
        } finally { f.close(); replacement.close() }
    }

    @Test fun boundaryInvalidationClearsReadbackWitnessAndNewLeaseOnlyHasHistoricalData() = runTest {
        val f = Harness(this)
        try {
            f.seed(); val (_, hold) = f.hold()
            val oldBytes = f.shared.store.records.getValue(KEY)
            f.shared.boundary.clear()
            val nextShared = Shared(existingStore = f.shared.store)
            val next = Harness(this, nextShared)
            try {
                next.publications { use -> failure(FailureReason.CONFLICT) { next.journal.observePublicationReadback(use, hold) } }
                assertContentEquals(oldBytes.payload.copyForCodec(), next.shared.store.records.getValue(KEY).payload.copyForCodec())
            } finally { next.close() }
        } finally { f.close() }
    }

    @Test fun invalidationDuringActualReadbackCannotReturnProofOrCommitAnotherRow() = runTest {
        val f = Harness(this)
        try {
            f.seed(); val (_, hold) = f.hold(); val writes = f.shared.store.writes
            f.shared.store.afterRead = { key, _ -> if (key == KEY) f.shared.boundary.clear() }
            failure(FailureReason.STALE_SESSION) { f.publications { f.journal.observePublicationReadback(it, hold) } }
            assertEquals(writes, f.shared.store.writes)
        } finally { f.shared.store.afterRead = { _, _ -> }; f.close() }
    }

    @Test fun observerRegistrationRequiresActualDraftMemberAndCannotOccurAfterAdmission() = runTest {
        val f = Harness(this)
        try {
            failure(FailureReason.STALE_SESSION) { f.journal.registerDraftPublicationObserver(DraftJournalParticipant()) {} }
            failure(FailureReason.CONFLICT) { f.journal.registerDraftPublicationObserver(f.publication.participant) {} }
            f.drafts { }
            failure(FailureReason.CONFLICT) { f.journal.registerDraftPublicationObserver(f.draft.participant) {} }
            f.noIo()
        } finally { f.close() }
    }

    @Test fun failedHoldCanRenewExactOriginalWithFreshUseAndFrozenBytesWithoutRevivingOldToken() = runTest {
        val f = Harness(this)
        lateinit var first: DraftMutationContribution
        lateinit var original: PublicationOriginalLinkV1
        lateinit var frozen: StoreMutation.Put
        try {
            f.seed()
            f.publications { use ->
                val pin = f.journal.readForPublication(use, CLIENT, 1); original = f.link(f.journal.reviewSnapshot(use, pin))
                first = f.journal.prepareHold(use, pin, original, f.publicationCodec.reservedPublishedBytes(f.pending(original)))
                frozen = f.journal.mutations(use, first).single() as StoreMutation.Put
                f.shared.store.failBefore = true
                failure(FailureReason.STORAGE_FAILURE) { f.commit(use, first) }
            }
            f.shared.now += 10
            f.publications { use ->
                val pin = f.journal.readForPublication(use, CLIENT, 1)
                val renewed = f.journal.prepareHold(use, pin, original, f.publicationCodec.reservedPublishedBytes(f.pending(original)))
                val change = f.journal.mutations(use, renewed).single() as StoreMutation.Put
                assertNotSame(first, renewed); assertNotSame(frozen, change)
                assertContentEquals(frozen.payload.copyForCodec(), change.payload.copyForCodec())
                assertEquals(frozen.expectedRevision, change.expectedRevision)
                failure(FailureReason.CONFLICT) { f.journal.mutations(use, first) }
                f.commit(use, renewed)
            }
            assertContentEquals(original.exactUtf8.copyForCodec(), f.current().publicationHold!!.link.exactUtf8.copyForCodec())
            assertEquals(1, f.shared.store.writes)
        } finally { f.close() }
    }

    @Test fun failedTerminalCanRenewOverActualUnrelatedEditWithoutChangingOriginalOrLosingThatEdit() = runTest {
        val f = Harness(this)
        lateinit var first: DraftMutationContribution
        try {
            f.seed(withOther = true); val (link, _) = f.hold()
            f.publications { use ->
                first = f.journal.prepareTerminal(use, link, f.receipt())
                f.shared.store.failBefore = true
                failure(FailureReason.STORAGE_FAILURE) { f.commit(use, first) }
            }
            f.edit(OTHER, "Newer unrelated local content")
            f.publications { use ->
                val renewed = f.journal.prepareTerminal(use, link, f.receipt())
                assertNotSame(first, renewed)
                failure(FailureReason.CONFLICT) { f.journal.mutations(use, first) }
                f.commit(use, renewed)
                failure(FailureReason.CONFLICT) { f.journal.prepareTerminal(use, link, f.receipt()) }
                assertNotNull(f.journal.observePublicationReadback(use, renewed))
            }
            assertEquals("Newer unrelated local content", f.current().locals.single().content.caption)
            assertContentEquals(link.exactUtf8.copyForCodec(), (f.current().terminals.single() as PostDraftTerminalV2.Published).link.exactUtf8.copyForCodec())
        } finally { f.close() }
    }

    @Test fun neverAttemptedDispatchChecksCurrentHoldAndTypingButDoesNotRedefineAttemptedReplay() = runTest {
        val f = Harness(this)
        try {
            f.seed(); val (link, _) = f.hold(); val original = link.originalIntentForComparison().call
            f.publications { use -> f.journal.requirePublicationNewDispatch(use, link, link.historicalReview.exactReviewedLocalSnapshot) }
            val edit = PostDraftCurrentEdit(f.snapshots.withText(f.snapshot(), "Pending typing", null, 2), listOf(f.snapshot()))
            PostDraftCurrentHeld.retainEdit(f.access, f.shared.boundary, edit)
            f.publications { use -> failure(FailureReason.CONFLICT) { f.journal.requirePublicationNewDispatch(use, link, link.historicalReview.exactReviewedLocalSnapshot) } }
            PostDraftCurrentHeld.clearEdit(f.access, f.shared.boundary, edit)
            f.edit(CLIENT, "Actually newer local text")
            f.publications { use ->
                failure(FailureReason.CONFLICT) { f.journal.requirePublicationNewDispatch(use, link, link.historicalReview.exactReviewedLocalSnapshot) }
                val actual = f.journal.requirePublicationState(use)
                // A newly obtained explicit original-retry ticket may separately pin the
                // newer durable snapshot; this data check itself grants no review consent.
                f.journal.requirePublicationNewDispatch(use, link, actual.locals.single())
                failure(FailureReason.CONFLICT) { f.journal.requirePublicationNewDispatch(use, link, f.snapshot(OTHER)) }
                val retained = actual.hold!!.original
                assertTrue(postSameCall(original, retained.originalIntentForComparison().call))
            }
            val published = document(PostDraftControllerTest.draft(CLIENT, "Caption").json().jsonObject +
                mapOf("status" to JsonPrimitive("published"), "publishedPostId" to JsonPrimitive(POST)))
            f.drafts { use ->
                val entry = f.journal.readCurrent(use)
                val observed = f.snapshots.withServerAssociation(entry.value.locals.single(), DraftServerAssociationV1.Observed(published, "\"1\""))
                f.journal.commitCurrent(use, f.journal.mutationCurrent(use, entry, entry.value.copy(locals = listOf(observed))))
            }
            f.publications { use ->
                val actual = f.journal.requirePublicationState(use)
                failure(FailureReason.CONFLICT) { f.journal.requirePublicationNewDispatch(use, link, actual.locals.single()) }
                assertTrue(postSameCall(original, actual.hold!!.original.originalIntentForComparison().call))
            }
            assertEquals(0, f.shared.calls)
        } finally { f.close() }
    }

    @Test fun currentRefreshRequiresTheActuallyObservedRecordAndPerformsFreshChangedCas() = runTest {
        val f = Harness(this)
        try {
            f.seed()
            f.drafts { use ->
                val observed = f.journal.readCurrent(use).record!!
                failure(FailureReason.CONFLICT) { f.journal.refreshCurrent(use, observed.copy(payload = PrivateBytes(observed.payload.copyForCodec()))) }
                val refresh = f.journal.refreshCurrent(use, observed)
                assertEquals(observed.revision, refresh.expectedRevision)
                assertContentEquals(observed.payload.copyForCodec(), refresh.payload.copyForCodec())
                f.journal.commitCurrent(use, refresh)
                assertEquals(observed.revision + 1, f.shared.store.records.getValue(KEY).revision)
            }
        } finally { f.close() }
    }

    @Test fun currentApplyChecksActualDomainArchiveBytesRevisionsAndStableSecondReadWithoutDeliveringAck() = runTest {
        val f = Harness(this)
        try {
            f.seed()
            val metadata = RecordKey("feedme.command.metadata", COMMAND)
            val priorMetadata = PrivateBytes("{\"syntheticArchive\":\"before\"}".encodeToByteArray())
            f.shared.store.records[metadata] = PrivateRecord(1, 1, priorMetadata)
            f.drafts { use ->
                val entry = f.journal.readCurrent(use)
                val original = PostDraftCommandV2.TextPatch(PostOriginal(COMMAND, "updatePostDraft", CLIENT, 1,
                    document(mapOf("caption" to JsonPrimitive("Caption"))), PostDraftControllerTest.draft(CLIENT, "Caption"), "\"1\"", NOW))
                val after = f.snapshots.withServerAssociation(entry.value.locals.single(),
                    DraftServerAssociationV1.Observed(PostDraftControllerTest.draft(CLIENT, "Caption", "2"), "\"2\""))
                val domain = f.journal.mutationCurrent(use, entry, entry.value.copy(locals = listOf(after), issued = entry.value.issued + COMMAND,
                    completion = PostDraftCompletionV2.Legacy(PostCompletion(COMMAND, "updatePostDraft", CLIENT, false))))
                val archive = StoreMutation.Put(metadata, 1, 1, PrivateBytes("{\"syntheticArchive\":\"actual-applied\"}".encodeToByteArray()))
                value(f.composition.store.commit(f.shared.scope, listOf(domain, archive)))
                val proof = PostDraftCurrentApply(original, domain, 9, false).also { it.archive = archive }
                val observed = f.journal.observeCurrentApply(use, proof)
                assertContentEquals(domain.payload.copyForCodec(), observed.first.payload.copyForCodec())
                assertContentEquals(archive.payload.copyForCodec(), observed.second.payload.copyForCodec())
                assertNull(proof.delivery) // Domain/archive comparison does not deliver a caller ACK.
                val actualMetadata = f.shared.store.records.getValue(metadata)
                f.shared.store.records[metadata] = actualMetadata.copy(payload = priorMetadata)
                failure(FailureReason.CONFLICT) { f.journal.observeCurrentApply(use, proof) }
                f.shared.store.records[metadata] = actualMetadata
                var disturbed = false
                f.shared.store.afterRead = { key, _ -> if (key == metadata && !disturbed) {
                    disturbed = true
                    val current = f.shared.store.records.getValue(KEY)
                    f.shared.store.records[KEY] = current.copy(revision = current.revision + 1)
                } }
                failure(FailureReason.CONFLICT) { f.journal.observeCurrentApply(use, proof) }
            }
        } finally { f.shared.store.afterRead = { _, _ -> }; f.close() }
    }

    @Test fun retirementPreparationPreservesEvidenceOnCancellationAndRejectsForgedStaleOrReusedTickets() = runTest {
        val f = Harness(this)
        lateinit var oldUse: DraftJournalUse
        lateinit var stale: DraftPublicationEvidenceRetirement
        try {
            f.seed(); val (_, hold) = f.hold()
            f.publications { use ->
                oldUse = use; stale = f.journal.preparePublicationEvidenceRetirement(use, hold)
                failure(FailureReason.CONFLICT) { f.journal.retirePublicationEvidence(use, DraftPublicationEvidenceRetirement()) }
                assertNotNull(f.journal.observePublicationReadback(use, hold))
            }
            f.publications { use ->
                failure(FailureReason.STALE_SESSION) { f.journal.retirePublicationEvidence(oldUse, stale) }
                failure(FailureReason.CONFLICT) { f.journal.retirePublicationEvidence(use, stale) }
                f.onCheck = { throw CancellationException("Synthetic preparation cancellation") }
                try { assertFailsWith<CancellationException> { f.journal.preparePublicationEvidenceRetirement(use, hold) } }
                finally { f.onCheck = {} }
                assertNotNull(f.journal.observePublicationReadback(use, hold))
                val ticket = f.journal.preparePublicationEvidenceRetirement(use, hold)
                val reads = f.shared.store.reads; val writes = f.shared.store.writes
                f.journal.retirePublicationEvidence(use, ticket)
                assertEquals(reads, f.shared.store.reads); assertEquals(writes, f.shared.store.writes)
                failure(FailureReason.CONFLICT) { f.journal.retirePublicationEvidence(use, ticket) }
                failure(FailureReason.CONFLICT) { f.journal.observePublicationReadback(use, hold) }
            }
            assertEquals(COMMAND, f.current().publicationHold!!.link.commandId) // Memory retirement is not lifecycle mutation.
        } finally { f.onCheck = {}; f.close() }
    }

    private class Shared(existingStore: PostDraftControllerTest.Store? = null) {
        val scope = StorageScope("synthetic-current-owner", ActorKind.ACCOUNT, "private-mapped-actor")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope)
        val store = existingStore ?: PostDraftControllerTest.Store(scope)
        var now = NOW; var calls = 0
        val transport = object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                calls++; return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
            }
        }
    }
    private class Member(val borrower: MealKitchenComposition.Borrower, val participant: DraftJournalParticipant)
    private class Harness(test: TestScope, val shared: Shared = Shared(), configured: Boolean = true) {
        val policy = PostDraftClientPolicy(8, 1_048_576, 262_144, 64, 64, 2, 100, 60_000)
        val publicationPolicy = PostPublicationClientPolicy(1_048_576, 65_536, 8192, 8, 32, 8, 8, 8, 8, 65_536, 4096, 60_000)
        val snapshots = DraftLocalSnapshotCodecV1(policy, publicationPolicy)
        val links = PublicationOriginalLinkCodecV1(publicationPolicy, snapshots)
        val codec = PostDraftV2Codec(shared.scope.environment, ORIGIN, policy, publicationPolicy, snapshots, links)
        val binding = PublicationJournalBindingData(shared.scope.environment, ORIGIN, ORIGIN, ACCOUNT)
        val publicationCodec = PostPublicationJournalCodecV1(binding, publicationPolicy, links)
        val cross = PublicationCrossRecordValidator(publicationCodec, links, snapshots, publicationPolicy, policy)
        val access = AuthenticatedMealPlanningAccess(shared.lease, ORIGIN, shared.store, shared.transport, true)
        val composition = MealKitchenComposition(access, shared.boundary, StandardTestDispatcher(test.testScheduler), EpochClock { shared.now }, ConnectivityPort { Connectivity.ONLINE })
        val journal = PostDraftJournalOwner(composition, policy, if (configured) publicationPolicy else null)
        var activeUse: DraftJournalUse? = null; var activeContribution: DraftMutationContribution? = null
        var onCheck: suspend () -> Unit = {}
        val notifications = mutableListOf<String>()
        val draft = member(MealKitchenFeature.POST_DRAFTS)
        val publication = member(MealKitchenFeature.POST_PUBLICATIONS)
        private var detached = false
        private fun member(feature: MealKitchenFeature): Member {
            val borrower = composition.bind(feature, object : MealKitchenHooks {
                override suspend fun checkCurrent() = onCheck()
                override fun beforeCommit(mutations: List<StoreMutation>) {
                    val use = activeUse ?: error("Synthetic harness lost its actual journal use")
                    if (feature == MealKitchenFeature.POST_DRAFTS) journal.beforeCurrentCommit(use, mutations)
                    else journal.beforePublicationCommit(use, activeContribution ?: error("No actual contribution"), mutations)
                }
            })
            return Member(borrower, journal.register(borrower))
        }
        suspend fun <T> drafts(action: suspend (DraftJournalUse) -> T): T = use(draft, action)
        suspend fun <T> publications(action: suspend (DraftJournalUse) -> T): T = use(publication, action)
        private suspend fun <T> use(member: Member, action: suspend (DraftJournalUse) -> T): T = composition.operate(member.borrower) {
            val use = journal.enter(composition.composerPermit(member.borrower), member.participant)
            activeUse = use
            try { action(use) } finally { activeContribution = null; activeUse = null; journal.leave(use) }
        }
        fun snapshot(client: String = CLIENT) = snapshots.create(client, 1, DraftLocalContentV1.TextV1("Caption", null), DraftServerAssociationV1.NotObserved)
        fun row(withOther: Boolean = false) = PostDraftV2Record(NOW, listOf(snapshot()) + if (withOther) listOf(snapshot(OTHER)) else emptyList(),
            listOf(CLIENT) + if (withOther) listOf(OTHER) else emptyList())
        fun seed(withOther: Boolean = false) { shared.store.records[KEY] = PrivateRecord(1, 2, codec.encode(row(withOther))) }
        fun current() = codec.decode(2, shared.store.records.getValue(KEY).payload)
        fun link(snapshot: DraftLocalSnapshotV1, command: String = COMMAND): PublicationOriginalLinkV1 {
            val disclosure = PublicationDisclosure("synthetic-v1", "Actual synthetic disclosure shown")
            val body = document(buildJsonObject { put("clientDraftId", snapshot.clientDraftId); put("caption", snapshot.content.caption)
                snapshot.content.altText?.let { put("altText", it) }; put("mediaIds", JsonArray(emptyList())); put("audience", postSelfAudience())
                put("keepOnPlate", false); put("allowRecipeSaves", false); put("saveDisclosureVersion", disclosure.version) })
            return links.create(binding, command, NOW, ApiCall("publishPost", body = PrivateBytes(body.encodeUtf8()), idempotencyKey = SecretText(command)),
                snapshot, PublicationReviewTargetV1.DirectLocal, disclosure)
        }
        fun pending(link: PublicationOriginalLinkV1) = PublicationJournalV1(binding, NOW, listOf(link.commandId), listOf(PublicationHistoryEntryV1.PendingOriginal(link)))
        suspend fun hold(): Pair<PublicationOriginalLinkV1, DraftMutationContribution> = publications { use ->
            val pin = journal.readForPublication(use, CLIENT, 1); val link = link(journal.reviewSnapshot(use, pin))
            val contribution = journal.prepareHold(use, pin, link, publicationCodec.reservedPublishedBytes(pending(link)))
            cross.requireConsistent(pending(link), journal.publicationProjection(use, contribution)); commit(use, contribution)
            link to contribution
        }
        suspend fun commit(use: DraftJournalUse, contribution: DraftMutationContribution) {
            activeContribution = contribution
            val actual = journal.mutations(use, contribution)
            val acknowledgement = mealValue(composition.store.commit(shared.scope, actual))
            journal.verifyPublicationReadback(use, contribution, acknowledgement)
        }
        suspend fun edit(root: String, caption: String, alt: String? = null) = drafts { use ->
            val entry = journal.readCurrent(use); val local = entry.value.locals.single { it.clientDraftId == root }
            val next = snapshots.withText(local, caption, alt, local.localRevision + 1)
            journal.commitCurrent(use, journal.mutationCurrent(use, entry, entry.value.copy(locals = entry.value.locals.map { if (it.clientDraftId == root) next else it })))
        }
        fun receipt(): PostPublicationReceipt {
            val body = document(buildJsonObject { put("id", POST); put("version", 1); put("createdAt", "2026-09-14T12:00:00Z"); put("updatedAt", "2026-09-14T12:00:00Z")
                put("author", buildJsonObject { put("userId", ACCOUNT); put("displayName", "Synthetic cook"); put("handle", "synthetic"); put("avatarMediaId", number(99)) })
                put("caption", "Caption"); put("mediaIds", JsonArray(emptyList())); put("audience", JsonObject(postSelfAudience() + ("bindings" to JsonArray(emptyList()))))
                put("status", "published"); put("publishedAt", "2026-09-14T12:00:00Z"); put("expiresAt", "2026-09-15T12:00:00Z"); put("keepOnPlate", false)
                put("savePolicy", buildJsonObject { put("allowFutureSaves", false); put("policyVersion", 1); put("disclosureVersion", "synthetic-v1") })
                put("aclVersion", 1); put("capabilities", JsonArray(listOf(JsonPrimitive("view"), JsonPrimitive("delete")))); put("reactionCounts", JsonArray(emptyList())) })
            return PostPublicationAdapter(publicationPolicy.maxResponseBytes).receipt(link(snapshot()).originalIntentForComparison().call,
                ACCOUNT, ApiReply(201, PrivateBytes(body.encodeUtf8()), "\"1\"", contentType = "application/json"))
        }
        fun noIo() { assertEquals(0, shared.store.reads); assertEquals(0, shared.store.writes); assertEquals(0, shared.store.erases); assertEquals(0, shared.calls) }
        fun detach() {
            if (detached) return
            detached = true
            journal.release(draft.participant); journal.release(publication.participant)
            composition.release(draft.borrower); composition.release(publication.borrower)
        }
        fun close() { shared.boundary.clear(); detach() }
    }
    private companion object {
        const val ORIGIN = "00000000-0000-4000-8000-000000000081"
        const val CLIENT = "00000000-0000-4000-8000-000000000082"
        const val OTHER = "00000000-0000-4000-8000-000000000083"
        const val COMMAND = "00000000-0000-4000-8000-000000000084"
        const val POST = "00000000-0000-4000-8000-000000000085"
        const val ACCOUNT = "00000000-0000-4000-8000-000000000086"
        const val NOW = 1_800_000_000_000L
        val KEY = RecordKey("mealflow.post-drafts.v1", ORIGIN)
        suspend fun failure(reason: FailureReason, action: suspend () -> Unit) = assertEquals(reason, assertFailsWith<MealFailure> { action() }.reason)
        fun <T> value(result: PortResult<T>) = assertIs<PortResult.Value<T>>(result).value
    }
}
