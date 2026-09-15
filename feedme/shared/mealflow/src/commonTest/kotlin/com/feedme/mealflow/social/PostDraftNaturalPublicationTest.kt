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

/** Actual public local editor -> sole owner publication handoff over a synthetic CAS store.
 * No dummy open/edit clears markers. Canonical Post data is NOT a queue receipt or publication ACK. */
@OptIn(ExperimentalCoroutinesApi::class)
class PostDraftNaturalPublicationTest {
    @Test fun actualNewLocalDraftCanBeReviewedAndHeldWithoutAnyCleanupAction() = runTest {
        val f = Fixture(this)
        try {
            val created = f.controller.newLocalDraft("New local moment", "").value()
            val root = created.selected!!.clientDraftId
            assertTrue(created.selected!!.localAcknowledged)
            val held = assertNotNull(PostDraftCurrentHeld.edit(f.access, f.boundary))
            assertTrue(held.delivery!!.delivered(held)); assertNotNull(f.current().localPending)
            val before = f.store.records.getValue(KEY); val writes = f.store.writes
            f.review(root)
            assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
            val link = f.hold(root)
            assertEquals(root, link.clientDraftId); assertEquals(writes + 1, f.store.writes)
            assertNull(f.current().localPending); assertSame(held, PostDraftCurrentHeld.edit(f.access, f.boundary))
            assertTrue(held.delivery!!.delivered(held)); assertFalse(f.controller.states.value.serverAcknowledged)
            assertEquals(1, f.ids); assertTrue(f.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun actualComposerEditFlowsDirectlyIntoReadOnlyReviewThenAtomicHold() = runTest {
        val f = Fixture(this)
        try {
            f.seedComposer(); f.open(CLIENT)
            val original = f.current().locals.single()
            val edit = f.controller.editCaption(CLIENT, "My full retained moment").value()
            assertTrue(edit.selected!!.localAcknowledged)
            val exact = f.current().locals.single(); f.assertChoices(original, exact)
            val pending = f.current().localPending; val before = f.store.records.getValue(KEY); val writes = f.store.writes
            f.publication { use ->
                val pin = f.owner.readForPublication(use, CLIENT, exact.localRevision)
                assertContentEquals(exact.exactUtf8.copyForCodec(), f.owner.reviewSnapshot(use, pin).exactUtf8.copyForCodec())
                val (link, contribution) = f.prepare(use, pin)
                assertTrue(postSame(before, f.store.records[KEY])); assertEquals(pending, f.current().localPending)
                assertEquals(writes, f.store.writes)
                assertContentEquals(exact.exactUtf8.copyForCodec(), link.historicalReview.exactReviewedLocalSnapshot.exactUtf8.copyForCodec())
                f.commitDraftContribution(use, contribution)
            }
            assertEquals(writes + 1, f.store.writes); assertNull(f.current().localPending)
            assertContentEquals(exact.exactUtf8.copyForCodec(), f.current().locals.single().exactUtf8.copyForCodec())
            assertFalse(f.controller.states.value.serverAcknowledged); assertEquals(0, f.ids); assertTrue(f.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun actualDeliveredNewerComposerEditAllowsFreshOriginalDispatchAndCompleteTerminalRemainder() = runTest {
        val f = Fixture(this)
        try {
            f.seedComposer(); f.open(CLIENT); val link = f.hold()
            val edited = f.controller.editCaption(CLIENT, "Newer not published").value()
            assertTrue(edited.selected!!.localAcknowledged); assertNotNull(f.current().localPending)
            val exact = f.current().locals.single(); val before = f.store.records.getValue(KEY); val writes = f.store.writes
            f.publication { use -> f.owner.requirePublicationNewDispatch(use, link, exact) }
            assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
            f.terminal(link, commit = true)
            assertNull(f.current().localPending); assertTrue(f.current().locals.isEmpty())
            val remainder = f.current().remainders.single()
            assertContentEquals(exact.content.let { assertIs<DraftLocalContentV1.ComposerV2>(it).exactChoices.encodeUtf8() },
                assertIs<DraftLocalContentV1.ComposerV2>(remainder.content).exactChoices.encodeUtf8())
            assertEquals(exact.localRevision, remainder.newerLocalRevision)
            assertContentEquals(link.exactUtf8.copyForCodec(), remainder.link.exactUtf8.copyForCodec())
            assertFalse(f.controller.states.value.serverAcknowledged); assertEquals(0, f.ids); assertTrue(f.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun failedLocalCommitBeforeOrAfterMutationCannotBePromotedToPublicationReview() = runTest {
        for (after in listOf(false, true)) {
            val f = Fixture(this)
            try {
                f.seedComposer(); f.open(CLIENT)
                if (after) f.store.failAfter = { changes -> changes.any { it.key == KEY } } else f.store.failBefore = true
                assertIs<PortResult.Failure>(f.controller.editCaption(CLIENT, "Unknown local save"))
                val proof = assertNotNull(PostDraftCurrentHeld.edit(f.access, f.boundary)); assertFalse(proof.delivery?.delivered(proof) == true)
                val before = f.store.records.getValue(KEY); val writes = f.store.writes
                assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> { f.review() }.reason)
                assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
                assertFalse(f.controller.states.value.serverAcknowledged); assertTrue(f.calls.isEmpty())
            } finally { f.close() }
        }
    }

    @Test fun historicalMarkerWithoutActualSameLeaseHeldProofIsNotLocalDelivery() = runTest {
        val f = Fixture(this)
        try {
            f.seedComposer(); f.open(CLIENT); f.controller.editCaption(CLIENT, "Delivered once").value()
            val before = f.store.records.getValue(KEY); val writes = f.store.writes
            PostDraftCurrentHeld.clear(f.access, f.boundary) // Explicit lost-process-proof simulation, not a new ACK.
            assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> { f.review() }.reason)
            assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
            assertNotNull(f.current().localPending); assertTrue(f.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun sameRootMarkerWithChangedCompleteSnapshotCannotReuseEarlierDeliveredEdit() = runTest {
        val f = Fixture(this)
        try {
            f.seedComposer(); f.open(CLIENT); f.controller.editCaption(CLIENT, "Actual delivered").value()
            val old = f.current(); val prior = f.store.records.getValue(KEY); val local = old.locals.single()
            val changed = f.snapshots.withText(local, local.content.caption, "Changed exact optional text", local.localRevision + 1)
            f.store.records[KEY] = PrivateRecord(prior.revision + 1, 2, f.codec.encode(old.copy(
                locals = listOf(changed), localPending = PostLocalPending(CLIENT, changed.localRevision))))
            val actual = f.store.records.getValue(KEY); val writes = f.store.writes
            assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> { f.review() }.reason)
            assertTrue(postSame(actual, f.store.records[KEY])); assertEquals(writes, f.store.writes); assertTrue(f.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun pinCannotLaunderChangedMutationIdentityEvenWhenReplacementBytesAreEqual() = runTest {
        val f = Fixture(this)
        try {
            f.seedComposer(); f.open(CLIENT); f.controller.editCaption(CLIENT, "Delivered").value()
            val pin = f.review(); val edit = assertNotNull(PostDraftCurrentHeld.edit(f.access, f.boundary))
            val original = edit.mutation!!; val before = f.store.records.getValue(KEY)
            edit.mutation = StoreMutation.Put(original.key, original.expectedRevision, original.schemaVersion, original.payload)
            assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> {
                f.publication { use -> f.owner.requirePin(use, pin) }
            }.reason)
            assertTrue(postSame(before, f.store.records[KEY])); assertTrue(f.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun unrelatedRootDeliveredMarkerSurvivesBothActualHoldAndTerminal() = runTest {
        val f = Fixture(this)
        try {
            f.seed(withOther = true); f.open(OTHER); f.controller.editCaption(OTHER, "Keep other local work").value()
            val pending = f.current().localPending; val other = f.current().locals.single { it.clientDraftId == OTHER }
            val proof = PostDraftCurrentHeld.edit(f.access, f.boundary)
            val link = f.hold(); assertEquals(pending, f.current().localPending)
            f.terminal(link, commit = true); assertEquals(pending, f.current().localPending)
            assertContentEquals(other.exactUtf8.copyForCodec(), f.current().locals.single().exactUtf8.copyForCodec())
            assertSame(proof, PostDraftCurrentHeld.edit(f.access, f.boundary)); assertTrue(proof!!.delivery!!.delivered(proof))
            assertEquals(OTHER, f.controller.states.value.selected!!.clientDraftId)
            assertFalse(f.controller.states.value.serverAcknowledged); assertTrue(f.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun realSameRootStagingAfterHoldPreparationFailsBeforeAnyAtomicCommit() = runTest {
        val f = Fixture(this); var edit: Deferred<PortResult<PostDraftState>>? = null
        try {
            f.seedComposer(); f.open(CLIENT); f.controller.editCaption(CLIENT, "Reviewed local").value()
            val before = f.store.records.getValue(KEY); val writes = f.store.writes
            f.publication { use ->
                val pin = f.owner.readForPublication(use, CLIENT, f.current().locals.single().localRevision)
                val (_, contribution) = f.prepare(use, pin)
                edit = async { f.controller.editCaption(CLIENT, "Racing local proposal") }
                runCurrent()
                val held = assertNotNull(PostDraftCurrentHeld.edit(f.access, f.boundary))
                assertEquals("Racing local proposal", held.proposed.content.caption)
                assertFalse(held.delivery?.delivered(held) == true)
                assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> {
                    f.commitDraftContribution(use, contribution)
                }.reason)
                assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
            }
            edit!!.await().value()
            assertNull(f.current().publicationHold); assertEquals("Racing local proposal", f.current().locals.single().content.caption)
            assertTrue(f.calls.isEmpty())
        } finally { withContext(NonCancellable) { edit?.cancelAndJoin() }; f.close() }
    }

    @Test fun failedBeforeCommitHoldLeavesDeliveredMarkerAndOriginalProofRecoverable() = runTest {
        val f = Fixture(this)
        try {
            f.seedComposer(); f.open(CLIENT); f.controller.editCaption(CLIENT, "Actual retained").value()
            val before = f.store.records.getValue(KEY); val pending = f.current().localPending
            val proof = PostDraftCurrentHeld.edit(f.access, f.boundary)
            f.store.failBefore = true
            assertEquals(FailureReason.STORAGE_FAILURE, assertFailsWith<MealFailure> { f.hold() }.reason)
            assertTrue(postSame(before, f.store.records[KEY])); assertEquals(pending, f.current().localPending)
            assertSame(proof, PostDraftCurrentHeld.edit(f.access, f.boundary)); assertTrue(proof!!.delivery!!.delivered(proof))
            f.hold(); assertNull(f.current().localPending); assertEquals(COMMAND, f.current().publicationHold!!.link.commandId)
            assertEquals(0, f.ids); assertTrue(f.calls.isEmpty()); assertFalse(f.controller.states.value.serverAcknowledged)
        } finally { f.close() }
    }

    @Test fun lostHoldCommitAckDoesNotEraseActualLocalProofOrInventPublicationAck() = runTest {
        val f = Fixture(this)
        try {
            f.seedComposer(); f.open(CLIENT); f.controller.editCaption(CLIENT, "Keep local proof").value()
            val proof = PostDraftCurrentHeld.edit(f.access, f.boundary)
            f.store.failAfter = { changes -> changes.any { it.key == KEY } }
            assertEquals(FailureReason.OUTCOME_UNKNOWN, assertFailsWith<MealFailure> { f.hold() }.reason)
            val current = f.current(); assertNull(current.localPending); assertEquals(COMMAND, current.publicationHold!!.link.commandId)
            assertSame(proof, PostDraftCurrentHeld.edit(f.access, f.boundary)); assertTrue(proof!!.delivery!!.delivered(proof))
            assertFalse(f.controller.states.value.serverAcknowledged); assertTrue(f.calls.isEmpty()); assertEquals(0, f.ids)
        } finally { f.close() }
    }

    @Test fun actualAcknowledgedServerSaveCanEnterPublicationReviewWithoutRetiringItsMarker() = runTest {
        val f = Fixture(this)
        try {
            f.seed(); f.open(CLIENT); f.enableTextSave()
            val saved = f.controller.saveExplicitly().value(); assertTrue(saved.serverAcknowledged)
            val proof = assertNotNull(PostDraftCurrentHeld.apply(f.access, f.boundary))
            assertTrue(proof.delivery!!.delivered(proof)); assertNotNull(f.current().completion)
            val before = f.store.records.getValue(KEY); val writes = f.store.writes; val calls = f.calls.size
            val pin = f.review()
            f.publication { use ->
                val snapshot = f.owner.reviewSnapshot(use, pin)
                assertContentEquals(f.current().locals.single().exactUtf8.copyForCodec(), snapshot.exactUtf8.copyForCodec())
            }
            assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
            assertEquals(calls, f.calls.size); assertEquals(1, f.ids)
            assertNotNull(f.current().completion); assertSame(proof, PostDraftCurrentHeld.apply(f.access, f.boundary))
        } finally { f.close() }
    }

    @Test fun historicalCompletionWithoutTheActualDeliveredApplyNeverAdmitsPublication() = runTest {
        val f = Fixture(this)
        try {
            f.seed(); f.open(CLIENT); f.enableTextSave(); assertTrue(f.controller.saveExplicitly().value().serverAcknowledged)
            val before = f.store.records.getValue(KEY); val writes = f.store.writes; val calls = f.calls.size
            PostDraftCurrentHeld.clear(f.access, f.boundary)
            assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> { f.review() }.reason)
            assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
            assertEquals(calls, f.calls.size); assertNotNull(f.current().completion)
        } finally { f.close() }
    }

    @Test fun deliveredSaveStillRequiresExactActualArchiveAndUnchangedFullDomain() = runTest {
        for (damage in 0..2) {
            val f = Fixture(this)
            try {
                f.seed(); f.open(CLIENT); f.enableTextSave(); f.controller.saveExplicitly().value()
                val proof = assertNotNull(PostDraftCurrentHeld.apply(f.access, f.boundary)); val archive = proof.archive!!
                if (damage == 0) f.store.records.remove(archive.key)
                if (damage == 1) f.store.records[archive.key] = f.store.records.getValue(archive.key).let { it.copy(revision = it.revision + 1) }
                if (damage == 2) {
                    val old = f.current(); val local = old.locals.single(); val row = f.store.records.getValue(KEY)
                    val changed = f.snapshots.withText(local, "Not the actually acknowledged domain", local.content.altText, local.localRevision + 1)
                    f.store.records[KEY] = PrivateRecord(row.revision + 1, 2, f.codec.encode(old.copy(locals = listOf(changed))))
                }
                val before = f.store.records.getValue(KEY); val writes = f.store.writes; val calls = f.calls.size
                assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> { f.review() }.reason)
                assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes); assertEquals(calls, f.calls.size)
                assertTrue(proof.delivery!!.delivered(proof)); assertNotNull(f.current().completion)
            } finally { f.close() }
        }
    }

    @Test fun savedReviewPinRejectsReplacementArchiveObjectEvenWithIdenticalPayload() = runTest {
        val f = Fixture(this)
        try {
            f.seed(); f.open(CLIENT); f.enableTextSave(); f.controller.saveExplicitly().value()
            val pin = f.review(); val proof = assertNotNull(PostDraftCurrentHeld.apply(f.access, f.boundary))
            val old = proof.archive!!
            proof.archive = StoreMutation.Put(old.key, old.expectedRevision, old.schemaVersion, old.payload)
            val before = f.store.records.getValue(KEY); val writes = f.store.writes
            assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> {
                f.publication { use -> f.owner.requirePin(use, pin) }
            }.reason)
            assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
        } finally { f.close() }
    }

    @Test fun genuineAllocatedOriginalRecoveryPinsNewerDurableWorkWithoutRewritingTheOriginal() = runTest {
        val f = Fixture(this)
        try {
            f.seedComposer(); f.open(CLIENT)
            val (allocation, original) = f.allocateOriginal()
            f.controller.editCaption(CLIENT, "Newer separately reviewed work").value()
            val current = f.current().locals.single(); val before = f.store.records.getValue(KEY); val writes = f.store.writes
            f.publication { use ->
                val pin = f.owner.readForPublication(use, CLIENT, current.localRevision)
                assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> {
                    f.owner.prepareHold(use, pin, original, f.reserved(original))
                }.reason) // New-root admission remains strict; recovery is a distinct opaque-original path.
                val projection = f.owner.preflightOriginalRegistration(use, pin, allocation, f.reserved(original))
                f.requireConsistent(original, projection)
                assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
                val contribution = f.owner.prepareOriginalRegistration(use, pin, allocation, f.reserved(original))
                f.requireConsistent(original, f.owner.publicationProjection(use, contribution))
                f.commitDraftContribution(use, contribution)
            }
            val held = f.current().publicationHold!!
            assertContentEquals(original.exactUtf8.copyForCodec(), held.link.exactUtf8.copyForCodec())
            assertContentEquals(current.exactUtf8.copyForCodec(), f.current().locals.single().exactUtf8.copyForCodec())
            assertEquals("Caption", held.link.historicalReview.exactReviewedLocalSnapshot.content.caption)
            assertNull(f.current().localPending); assertEquals(1, f.ids); assertTrue(f.calls.isEmpty())
            assertFalse(f.controller.states.value.serverAcknowledged)
        } finally { f.close() }
    }

    @Test fun originalRecoveryRejectsStalePinAndForgedOrForeignAllocationWithoutWrites() = runTest {
        val f = Fixture(this); val foreign = Fixture(this)
        try {
            f.seedComposer(); f.open(CLIENT); foreign.seedComposer()
            val (allocation, original) = f.allocateOriginal(); val (other, _) = foreign.allocateOriginal()
            val stalePin = f.review(); f.controller.editCaption(CLIENT, "New pin required").value()
            val before = f.store.records.getValue(KEY); val writes = f.store.writes
            f.publication { use ->
                assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> {
                    f.owner.prepareOriginalRegistration(use, stalePin, allocation, f.reserved(original))
                }.reason)
                val currentPin = f.owner.readForPublication(use, CLIENT, f.current().locals.single().localRevision)
                for (invalid in listOf(PublicationAllocatedOriginal(), other)) assertEquals(FailureReason.CONFLICT,
                    assertFailsWith<MealFailure> {
                        f.owner.preflightOriginalRegistration(use, currentPin, invalid, f.reserved(original))
                    }.reason)
            }
            assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
            assertNull(f.current().publicationHold); assertEquals(1, f.ids); assertTrue(f.calls.isEmpty())
        } finally { foreign.close(); f.close() }
    }

    @Test fun originalRecoveryRejectsUnknownLocalEditAndPreviouslyIssuedCommandLineage() = runTest {
        for (unknownEdit in listOf(true, false)) {
            val f = Fixture(this)
            try {
                f.seedComposer(); f.open(CLIENT); val (allocation, original) = f.allocateOriginal()
                if (unknownEdit) {
                    f.store.failAfter = { changes -> changes.any { it.key == KEY } }
                    assertIs<PortResult.Failure>(f.controller.editCaption(CLIENT, "Unknown newer work"))
                } else {
                    val row = f.store.records.getValue(KEY); val current = f.current()
                    f.store.records[KEY] = PrivateRecord(row.revision + 1, 2,
                        f.codec.encode(current.copy(issued = current.issued + original.commandId)))
                }
                val before = f.store.records.getValue(KEY); val writes = f.store.writes
                assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> {
                    f.publication { use ->
                        val pin = f.owner.readForPublication(use, CLIENT, f.current().locals.single().localRevision)
                        f.owner.prepareOriginalRegistration(use, pin, allocation, f.reserved(original))
                    }
                }.reason)
                assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
                assertNull(f.current().publicationHold); assertEquals(1, f.ids); assertTrue(f.calls.isEmpty())
            } finally { f.close() }
        }
    }

    @Test fun concreteAllocationRegistryCannotBeReboundOrLaunderedAcrossOwners() = runTest {
        val f = Fixture(this); val foreign = Fixture(this)
        try {
            assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> {
                f.owner.registerPublicationAllocations(f.participant, foreign.allocations)
            }.reason)
            assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> {
                f.owner.registerPublicationAllocations(f.participant, f.allocations)
            }.reason)
            assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> {
                f.allocations.requireAssembly(foreign.composition, foreign.owner, foreign.borrower)
            }.reason)
            assertEquals(0, f.store.writes); assertEquals(0, foreign.store.writes)
            assertEquals(0, f.ids); assertEquals(0, foreign.ids); assertTrue(f.calls.isEmpty()); assertTrue(foreign.calls.isEmpty())
        } finally { foreign.close(); f.close() }
    }

    @Test fun sameRootReviewedSaveReservationExcludesPublicationButUnrelatedRootRemainsIndependent() = runTest {
        val f = Fixture(this)
        try {
            f.seed(withOther = true)
            f.publication { use ->
                val slot = ComposerAllocationArbiter.reserveReviewedSave(f.access, f.boundary, CLIENT)
                try {
                    assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> {
                        f.owner.readForPublication(use, CLIENT, 1)
                    }.reason)
                    val unrelated = f.owner.readForPublication(use, OTHER, 3)
                    assertEquals(OTHER, f.owner.reviewSnapshot(use, unrelated).clientDraftId)
                } finally { ComposerAllocationArbiter.release(slot) }
            }
            val link = f.hold()
            f.publication { use ->
                val slot = ComposerAllocationArbiter.reserveReviewedSave(f.access, f.boundary, CLIENT)
                try {
                    val before = f.store.records.getValue(KEY); val writes = f.store.writes
                    assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> {
                        f.owner.requirePublicationNewDispatch(use, link, f.current().locals.single { it.clientDraftId == CLIENT })
                    }.reason)
                    assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> {
                        f.owner.prepareTerminal(use, link, f.receipt(link))
                    }.reason)
                    assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
                } finally { ComposerAllocationArbiter.release(slot) }
            }
            assertEquals(0, f.ids); assertTrue(f.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun originalRecoveryNeverSwitchesDirectBranchWhenASeparateServerAssociationAppears() = runTest {
        val f = Fixture(this)
        try {
            f.seedComposer(); val (allocation, original) = f.allocateOriginal()
            f.observeSaved()
            val before = f.store.records.getValue(KEY); val writes = f.store.writes
            f.publication { use ->
                val pin = f.owner.readForPublication(use, CLIENT, 1)
                assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> {
                    f.owner.prepareOriginalRegistration(use, pin, allocation, f.reserved(original))
                }.reason)
            }
            assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
            assertIs<PublicationReviewTargetV1.DirectLocal>(original.historicalReview.target)
            assertNull(f.current().publicationHold); assertEquals(1, f.ids); assertTrue(f.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun savedOriginalRecoveryAllowsOnlySemanticallyIdenticalObservationWithoutChangingOriginalBytes() = runTest {
        for (materialChange in listOf(false, true)) {
            val f = Fixture(this)
            try {
                f.seedComposer(); f.observeSaved(); val (allocation, original) = f.allocateOriginal()
                val old = f.current(); val local = old.locals.single()
                val associated = assertIs<DraftServerAssociationV1.Observed>(local.serverAssociation)
                val fields = associated.exactPostDraft.json().jsonObject.entries.reversed().associate { it.key to it.value }.toMutableMap()
                if (materialChange) fields["altText"] = JsonPrimitive("Different optional text")
                f.replaceObservation(document(fields), associated.etag)
                val observed = f.current().locals.single(); val before = f.store.records.getValue(KEY); val writes = f.store.writes
                f.publication { use ->
                    val pin = f.owner.readForPublication(use, CLIENT, observed.localRevision)
                    if (materialChange) assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> {
                        f.owner.prepareOriginalRegistration(use, pin, allocation, f.reserved(original))
                    }.reason) else {
                        val next = f.owner.prepareOriginalRegistration(use, pin, allocation, f.reserved(original))
                        f.requireConsistent(original, f.owner.publicationProjection(use, next))
                        assertContentEquals(original.exactUtf8.copyForCodec(),
                            f.owner.publicationProjection(use, next).hold!!.original.exactUtf8.copyForCodec())
                    }
                }
                assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
                assertNull(f.current().publicationHold); assertEquals(1, f.ids); assertTrue(f.calls.isEmpty())
            } finally { f.close() }
        }
    }

    @Test fun returnedOriginalReservesIssuedCapacityBeforeAnotherLocalIdButAllowsUnrelatedEdits() = runTest {
        val f = Fixture(this, maxIssued = 3)
        try {
            f.seed(withOther = true); val (allocation, original) = f.allocateOriginal()
            val before = f.store.records.getValue(KEY); val writes = f.store.writes
            assertEquals(FailureReason.UNAVAILABLE, assertIs<PortResult.Failure>(f.controller.newLocalDraft("No spare identity")).reason)
            assertEquals(1, f.ids); assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
            f.open(OTHER); assertTrue(f.controller.editCaption(OTHER, "Unrelated edit still fits").value().selected!!.localAcknowledged)
            f.publication { use ->
                val pin = f.owner.readForPublication(use, CLIENT, 1)
                val contribution = f.owner.prepareOriginalRegistration(use, pin, allocation, f.reserved(original))
                f.commitDraftContribution(use, contribution)
            }
            assertEquals(OTHER, f.current().localPending!!.clientId)
            assertEquals(1, f.ids); assertTrue(f.calls.isEmpty()); assertEquals(original.commandId, f.current().publicationHold!!.link.commandId)
        } finally { f.close() }
    }

    @Test fun allocatedOriginalShadowRejectsOnlyAFullRowThatWouldConsumeItsFinalizationBytes() = runTest {
        val f = Fixture(this, maxRecordBytes = 65_536, maxLocals = 64)
        try {
            f.seedComposer(); val (allocation, original) = f.allocateOriginal()
            val actual = f.current(); val before = f.store.records.getValue(KEY); val writes = f.store.writes
            f.owner.preflightCurrentData(actual) // Small unrelated changes remain allowed.
            var rejected: PostDraftV2Record? = null
            for (count in 1..63) {
                val added = (1..count).map { n -> f.snapshots.create(number(2000 + n), 1,
                    DraftLocalContentV1.TextV1("c".repeat(500), "a".repeat(500)), DraftServerAssociationV1.NotObserved) }
                val candidate = actual.copy(locals = actual.locals + added, issued = actual.issued + added.map { it.clientDraftId })
                val withoutShadow = runCatching { f.codec.encode(candidate) }.getOrNull() ?: break
                val failure = runCatching { f.owner.preflightCurrentData(candidate) }.exceptionOrNull()
                if (failure != null) {
                    assertEquals(FailureReason.UNAVAILABLE, assertIs<MealFailure>(failure).reason)
                    assertTrue(withoutShadow.copyForCodec().size <= 65_536)
                    rejected = candidate; break
                }
            }
            assertNotNull(rejected) // Fits actual row alone; retained original/remainder reserve is the differentiator.
            assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
            f.publication { use ->
                val pin = f.owner.readForPublication(use, CLIENT, 1)
                f.owner.prepareOriginalRegistration(use, pin, allocation, f.reserved(original))
            }
            assertEquals(1, f.ids); assertTrue(f.calls.isEmpty()); assertNull(f.current().publicationHold)
        } finally { f.close() }
    }

    @Test fun actualReviewedSaveThenAllocationAndDeliveredEditRetainsOriginalAcrossFailedRegistrationRetry() = runTest {
        val f = Fixture(this)
        try {
            f.acknowledgeReviewedSave()
            val saved = f.current().locals.single()
            val (allocation, original) = f.allocateOriginal()
            val savedTarget = assertIs<PublicationReviewTargetV1.SavedDraft>(original.historicalReview.target)
            assertEquals("2", savedTarget.draftVersion.decimal)
            assertEquals(1, f.calls.size); assertEquals(2, f.ids)
            assertTrue(f.controller.editCaption(CLIENT, "Newer work stays private").value().selected!!.localAcknowledged)
            val newer = f.current().locals.single(); f.assertChoices(saved, newer)
            val edit = assertNotNull(PostDraftCurrentHeld.edit(f.access, f.boundary))
            val before = f.store.records.getValue(KEY); val writes = f.store.writes
            f.store.failBefore = true
            assertEquals(FailureReason.STORAGE_FAILURE, assertFailsWith<MealFailure> {
                f.registerOriginal(allocation, original)
            }.reason)
            assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
            assertSame(allocation, f.allocations.retained()); assertTrue(edit.delivery!!.delivered(edit))
            assertNull(f.current().publicationHold); assertNotNull(f.current().localPending)
            f.registerOriginal(allocation, original)
            assertNull(f.current().localPending)
            assertContentEquals(original.exactUtf8.copyForCodec(), f.current().publicationHold!!.link.exactUtf8.copyForCodec())
            f.publication { use -> f.owner.requirePublicationNewDispatch(use, original, newer) }
            f.terminal(original, commit = true) // Owner component only; no publication queue receipt or caller ACK.
            val remainder = f.current().remainders.single()
            assertEquals(newer.localRevision, remainder.newerLocalRevision)
            assertContentEquals(assertIs<DraftLocalContentV1.ComposerV2>(newer.content).exactChoices.encodeUtf8(),
                assertIs<DraftLocalContentV1.ComposerV2>(remainder.content).exactChoices.encodeUtf8())
            assertContentEquals(original.exactUtf8.copyForCodec(), remainder.link.exactUtf8.copyForCodec())
            assertSame(allocation, f.allocations.retained()) // Only the real coordinator Attempt may transfer it.
            assertFalse(f.controller.states.value.serverAcknowledged)
            assertEquals(listOf("updatePostDraft"), f.calls.map { it.operationId }); assertEquals(2, f.ids)
        } finally { f.close() }
    }

    @Test fun materialEditAfterReviewedSaveCannotAllocateANewSavedOriginalWithoutAnotherExplicitSave() = runTest {
        val f = Fixture(this)
        try {
            f.acknowledgeReviewedSave()
            assertTrue(f.controller.editCaption(CLIENT, "Not saved on the server").value().selected!!.localAcknowledged)
            val current = f.current().locals.single()
            val observed = assertIs<DraftServerAssociationV1.Observed>(current.serverAssociation)
            assertNotEquals(current.content.caption, postString(observed.exactPostDraft, "caption"))
            val before = f.store.records.getValue(KEY); val writes = f.store.writes; val calls = f.calls.size; val ids = f.ids
            assertEquals(FailureReason.INVALID_DATA, assertFailsWith<MealFailure> { f.allocateOriginal() }.reason)
            assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
            assertEquals(calls, f.calls.size); assertEquals(ids, f.ids); assertNull(f.allocations.retained())
            assertNull(PublicationAllocationRegistry.capacityCandidate(f.access, f.boundary))
            assertNull(f.current().publicationHold); assertNotNull(f.current().localPending)
            assertFalse(f.controller.states.value.serverAcknowledged)
        } finally { f.close() }
    }

    @Test fun sameSessionOwnerRecreationRetainsAllocatedOriginalAndLostHoldReadbackWithoutRevivingWriteAuthority() = runTest {
        val f = Fixture(this)
        try {
            f.acknowledgeReviewedSave()
            val (allocation, original) = f.allocateOriginal()
            f.controller.editCaption(CLIENT, "Durable newer work before owner replacement").value()
            val newer = f.current().locals.single(); val oldPin = f.review()
            val before = f.store.records.getValue(KEY); val ids = f.ids; val calls = f.calls.size
            f.reopen()
            assertSame(allocation, f.allocations.retained())
            assertTrue(postSame(before, f.store.records[KEY])); assertEquals(ids, f.ids); assertEquals(calls, f.calls.size)
            f.publication { use -> assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> {
                f.owner.requirePin(use, oldPin)
            }.reason) }
            var attempted: DraftMutationContribution? = null
            f.store.failAfter = { it.any { change -> change.key == KEY } }
            assertEquals(FailureReason.OUTCOME_UNKNOWN, assertFailsWith<MealFailure> {
                f.publication { use ->
                    val pin = f.owner.readForPublication(use, CLIENT, newer.localRevision)
                    val contribution = f.owner.prepareOriginalRegistration(use, pin, allocation, f.reserved(original))
                    attempted = contribution
                    f.commitDraftContribution(use, contribution)
                }
            }.reason)
            val held = f.store.records.getValue(KEY); val contribution = assertNotNull(attempted)
            f.reopen()
            assertSame(allocation, f.allocations.retained())
            assertContentEquals(original.exactUtf8.copyForCodec(), f.current().publicationHold!!.link.exactUtf8.copyForCodec())
            f.publication { use ->
                assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> {
                    f.owner.mutations(use, contribution)
                }.reason)
                val readback = f.owner.observePublicationReadback(use, contribution)
                val finalization = f.owner.preparePublicationFinalization(use, readback)
                f.commitDraftContribution(use, finalization)
            }
            assertTrue(f.store.records.getValue(KEY).revision > held.revision)
            assertContentEquals(newer.exactUtf8.copyForCodec(), f.current().locals.single().exactUtf8.copyForCodec())
            assertSame(allocation, f.allocations.retained()); assertEquals(ids, f.ids); assertEquals(calls, f.calls.size)
            assertFalse(f.controller.states.value.serverAcknowledged)
        } finally { f.close() }
    }

    @Test fun cancelledActualReviewedSaveTransportBlocksPublicationUntilExplicitExactOriginalRecovery() = runTest {
        val f = Fixture(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var save: Deferred<PortResult<PostDraftState>>? = null; var cancelledTransport = false
        try {
            val token = f.prepareActualReviewedSave()
            val reply = f.handler
            f.handler = { call ->
                entered.complete(Unit)
                try { release.await(); reply(call) } finally { cancelledTransport = !currentCoroutineContext().isActive }
            }
            save = async { f.controller.confirmReviewedSave(token) }; entered.await()
            val sent = f.calls.single(); save.cancel()
            assertFailsWith<CancellationException> { save.await() }
            assertTrue(cancelledTransport); assertFalse(f.controller.states.value.serverAcknowledged)
            val original = assertIs<PostDraftCommandV2.ReviewedPatch>(f.current().command)
            assertTrue(postSameCall(sent, original.historicalCall()))
            val before = f.store.records.getValue(KEY); val writes = f.store.writes
            assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> { f.review() }.reason)
            assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
            assertEquals(1, f.ids); assertNull(f.allocations.retained())
            f.handler = reply; f.reopen()
            val restored = f.controller.states.value
            assertFalse(restored.serverAcknowledged); assertNotNull(restored.pending)
            f.time = assertNotNull(restored.earliestRetryAtMillis) + 1
            val retry = value(f.controller.prepareReviewedOriginalRetry())
            assertEquals(ReviewedDraftRetryKind.ATTEMPTED_ORIGINAL_REPLAY, retry.snapshot.kind)
            assertTrue(f.controller.confirmReviewedOriginalRetry(retry.token).value().serverAcknowledged)
            assertEquals(1, f.ids); assertEquals(2, f.calls.size); assertTrue(postSameCall(sent, f.calls.last()))
            val pin = f.review()
            f.publication { use -> assertEquals("2",
                postVersion(assertIs<DraftServerAssociationV1.Observed>(f.owner.reviewSnapshot(use, pin).serverAssociation).exactPostDraft)) }
            assertEquals(2, f.calls.size); assertNull(f.current().publicationHold)
        } finally { release.complete(Unit); withContext(NonCancellable) { save?.cancelAndJoin(); f.close() } }
    }

    private class Fixture(test: TestScope, maxIssued: Int = 64, maxRecordBytes: Int = 1_048_576, maxLocals: Int = 8) {
        val policy = PostDraftClientPolicy(maxLocals, maxRecordBytes, 262_144, maxIssued, 64, 2, 100, 60_000)
        val publicationPolicy = PostPublicationClientPolicy(1_048_576, 65_536, 8192, 8, 32, 8, 8, 8, 8, 65_536, 4096, 60_000)
        val snapshots = DraftLocalSnapshotCodecV1(policy, publicationPolicy)
        val links = PublicationOriginalLinkCodecV1(publicationPolicy, snapshots)
        val scope = StorageScope("synthetic-joint-observer", ActorKind.ACCOUNT, "opaque-private-actor")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope)
        val store = PostDraftControllerTest.Store(scope)
        val calls = mutableListOf<ApiCall>(); var ids = 0; var time = NOW
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        private val disclosure = PublicationDisclosure("synthetic-v1", "Actual synthetic disclosure data")
        var handler: suspend (ApiCall) -> PortResult<ApiReply> = { PortResult.Failure(FailureReason.NOT_CONFIGURED) }
        val access = AuthenticatedMealPlanningAccess(lease, ORIGIN, store, object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                calls += call; return handler(call)
            }
        }, true)
        private val principalSource = object : PostPublicationPrincipalIntegration() {
            private val retainedOwner = Any(); private val generation = Any()
            override suspend fun resolve(binding: PublicationSessionBinding): PortResult<PublicationPrincipalSnapshot> =
                if (matchesSession(binding, access, boundary)) PortResult.Value(mappedPrincipal(binding, ACCOUNT, retainedOwner, generation))
                else PortResult.Failure(FailureReason.STALE_SESSION)
            override suspend fun requireCurrent(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot): PortResult<Unit> =
                if (isCurrent(binding, principal)) PortResult.Value(Unit) else PortResult.Failure(FailureReason.STALE_SESSION)
            override fun isCurrent(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot) =
                matchesSession(binding, access, boundary) && matchesCurrentPrincipal(binding, principal, retainedOwner, generation)
        }
        // Required explicit SYNTHETIC prerequisites. They share the actual fixture mapping;
        // neither owner nor production factory derives authority from these assertions.
        private val saveDelivery = object : PostPublicationDeliveryIntegration(principalSource, maxPendingDeliveries = 8) {
            override fun capture(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot): PortResult<PublicationDeliveryWitness> =
                PortResult.Value(witness(binding, principal, generationFor(principal)))
            fun dispose() = revokeCurrent()
        }
        private val savePrerequisites = object : ReviewedDraftSavePrerequisites {
            override val principals = principalSource
            override val delivery = saveDelivery
            private fun current(owner: ReviewedDraftPrerequisiteContext) {
                assertSame(lease, owner.lease); assertSame(boundary, owner.boundary)
                assertEquals(ORIGIN, owner.origin); assertTrue(principalSource.isCurrent(owner.principal.binding, owner.principal))
            }
            override suspend fun disclosure(owner: ReviewedDraftPrerequisiteContext): PortResult<PublicationDisclosure> {
                current(owner); return PortResult.Value(this@Fixture.disclosure)
            }
            override suspend fun requireNewSave(owner: ReviewedDraftPrerequisiteContext, check: ReviewedDraftNewSaveCheck): PortResult<Unit> {
                current(owner); assertEquals(owner.clientDraftId, check.exactCurrentLocalSnapshot.clientDraftId); return PortResult.Value(Unit)
            }
            override suspend fun requireOriginalReplay(owner: ReviewedDraftPrerequisiteContext, check: ReviewedDraftOriginalReplayCheck): PortResult<Unit> {
                current(owner); assertTrue(check.observedAttempts > 0); assertEquals(owner.clientDraftId, check.original.clientId); return PortResult.Value(Unit)
            }
        }
        private fun newComposition() = MealKitchenComposition(access, boundary, dispatcher, EpochClock { time }, ConnectivityPort { Connectivity.ONLINE })
        var composition = newComposition()
            private set
        var owner = PostDraftJournalOwner(composition, policy, publicationPolicy)
            private set
        private fun newController() = PostDraftController(composition, MealOperationIds { ids++; number(100 + ids) }, policy, owner, savePrerequisites)
        var controller = newController()
            private set
        private var activeUse: DraftJournalUse? = null
        private var activeContribution: DraftMutationContribution? = null
        private fun newBorrower() = composition.bind(MealKitchenFeature.POST_PUBLICATIONS, object : MealKitchenHooks {
            override suspend fun checkCurrent() = Unit
            override fun beforeCommit(mutations: List<StoreMutation>) {
                owner.beforePublicationCommit(activeUse ?: error("No current synthetic publication use"),
                    activeContribution ?: error("No actual owner contribution"), mutations)
            }
        })
        var borrower = newBorrower()
            private set
        var participant = owner.register(borrower)
            private set
        private var principalAdmission = PublicationPrincipalAdmission(composition, borrower, principalSource)
        private fun newAllocations() = PublicationAllocationRegistry(composition, owner, borrower, principalAdmission,
            MealOperationIds { ids++; number(900 + ids) }, policy, publicationPolicy)
        var allocations = newAllocations()
            private set
        init { owner.registerPublicationAllocations(participant, allocations) }
        val codec = PostDraftV2Codec(scope.environment, ORIGIN, policy, publicationPolicy, snapshots, links)
        private val binding = PublicationJournalBindingData(scope.environment, ORIGIN, ORIGIN, ACCOUNT)
        private val publicationCodec = PostPublicationJournalCodecV1(binding, publicationPolicy, links)
        private val cross = PublicationCrossRecordValidator(publicationCodec, links, snapshots, publicationPolicy, policy)
        fun current() = codec.decode(2, store.records.getValue(KEY).payload)
        suspend fun reopen() {
            check(activeUse == null)
            controller.close(); owner.release(participant); composition.release(borrower)
            composition = newComposition(); owner = PostDraftJournalOwner(composition, policy, publicationPolicy)
            controller = newController(); borrower = newBorrower(); participant = owner.register(borrower)
            principalAdmission = PublicationPrincipalAdmission(composition, borrower, principalSource)
            allocations = newAllocations(); owner.registerPublicationAllocations(participant, allocations)
            controller.restoreLocal().value()
        }
        suspend fun prepareActualReviewedSave(): PreparedReviewedDraftSave {
            seedComposer(); observeSaved(); open(CLIENT)
            controller.editCaption(CLIENT, "Privately saved caption").value()
            val local = current().locals.single()
            val association = assertIs<DraftServerAssociationV1.Observed>(local.serverAssociation)
            val baseline = association.exactPostDraft
            handler = { call ->
                if (call.operationId != "updatePostDraft") PortResult.Failure(FailureReason.NOT_CONFIGURED)
                else {
                    val expected = ReviewedPostDraftAdapter(publicationPolicy.maxResponseBytes)
                        .expectedFields(call, baseline, association.etag).json().jsonObject
                    val reply = document(expected + mapOf("updatedAt" to JsonPrimitive(TIME), "expiresAt" to JsonPrimitive("2030-01-01T00:00:00Z")))
                    PortResult.Value(ApiReply(200, PrivateBytes(reply.encodeUtf8()), etag = "\"2\"", contentType = "application/json"))
                }
            }
            val target = PublicationTarget.SavedDraft(CLIENT, local.localRevision, postString(baseline, "id"),
                ExactPostVersion(postVersion(baseline)), association.etag)
            val patch = ReviewedDraftPatch(PatchValue.Unchanged, PatchValue.Set(local.content.caption), PatchValue.Unchanged,
                PatchValue.Unchanged, PatchValue.Unchanged, PatchValue.Unchanged, PatchValue.Unchanged,
                PatchValue.Unchanged, PatchValue.Unchanged, PatchValue.Set(disclosure), PatchValue.Unchanged)
            return value(controller.prepareReviewedSave(target, patch)).token
        }
        suspend fun acknowledgeReviewedSave() {
            assertTrue(controller.confirmReviewedSave(prepareActualReviewedSave()).value().serverAcknowledged)
            assertIs<PostDraftCompletionV2.ReviewedApplied>(current().completion)
        }
        suspend fun registerOriginal(allocation: PublicationAllocatedOriginal, original: PublicationOriginalLinkV1) = publication { use ->
            val pin = owner.readForPublication(use, CLIENT, current().locals.single { it.clientDraftId == CLIENT }.localRevision)
            requireConsistent(original, owner.preflightOriginalRegistration(use, pin, allocation, reserved(original)))
            val contribution = owner.prepareOriginalRegistration(use, pin, allocation, reserved(original))
            requireConsistent(original, owner.publicationProjection(use, contribution))
            commitDraftContribution(use, contribution)
        }
        fun enableTextSave() {
            handler = { call ->
                if (call.operationId != "createPostDraft") PortResult.Failure(FailureReason.NOT_CONFIGURED)
                else {
                    val input = WireDocument.decode(call.body!!.copyForCodec()).json().jsonObject
                    val actual = document(PostDraftControllerTest.draft(CLIENT, input.getValue("caption").jsonPrimitive.content).json().jsonObject + input)
                    PostDraftControllerTest.response(actual, 201)
                }
            }
        }
        fun seed(withOther: Boolean = false) {
            val local = snapshots.create(CLIENT, 1, DraftLocalContentV1.TextV1("Caption", null), DraftServerAssociationV1.NotObserved)
            val choices = document(buildJsonObject {
                put("caption", "Other full composer"); put("altText", "")
                put("mediaIds", JsonArray(listOf(JsonPrimitive(number(41)), JsonPrimitive(number(40)))))
                put("audience", buildJsonObject { put("kind", "circles"); put("circleIds", JsonArray(listOf(JsonPrimitive(number(43)), JsonPrimitive(number(42))))) })
                put("keepOnPlate", true); put("allowRecipeSaves", true); put("saveDisclosureVersion", "retained-version")
                put("sourcePostId", SOURCE.uppercase()); put("attachment", buildJsonObject {
                    put("recipeVersionId", SOURCE.uppercase()); put("reviewStatus", "reviewed"); put("rightsBasis", "catalogRedistributable")
                    put("confirmedChanges", JsonArray(listOf(JsonPrimitive("Retain complete source"))))
                })
            })
            val other = snapshots.create(OTHER, 3, DraftLocalContentV1.ComposerV2(choices, "Retained disclosure text"), DraftServerAssociationV1.NotObserved)
            val locals = listOf(local) + if (withOther) listOf(other) else emptyList()
            store.records[KEY] = PrivateRecord(1, 2, codec.encode(PostDraftV2Record(NOW, locals, locals.map { it.clientDraftId })))
        }
        suspend fun open(root: String) { controller.restoreLocal().value(); controller.openLocal(root).value() }
        suspend fun <T> publication(action: suspend (DraftJournalUse) -> T): T = composition.operate(borrower) {
            val use = owner.enter(composition.composerPermit(borrower), participant); activeUse = use
            try { action(use) } finally { activeContribution = null; activeUse = null; owner.leave(use) }
        }
        fun seedComposer() {
            val choices = document(buildJsonObject {
                put("caption", "Caption"); put("altText", ""); put("mediaIds", JsonArray(emptyList()))
                put("audience", postSelfAudience()); put("keepOnPlate", true); put("allowRecipeSaves", false)
                put("saveDisclosureVersion", disclosure.version); put("sourcePostId", SOURCE.uppercase())
            })
            val local = snapshots.create(CLIENT, 1, DraftLocalContentV1.ComposerV2(choices, disclosure.text), DraftServerAssociationV1.NotObserved)
            store.records[KEY] = PrivateRecord(1, 2, codec.encode(PostDraftV2Record(NOW, listOf(local), listOf(CLIENT))))
        }
        /** Independent canonical GET data only, not a Save acknowledgement or publication review. */
        fun observeSaved() {
            val local = current().locals.single { it.clientDraftId == CLIENT }
            val choices = assertIs<DraftLocalContentV1.ComposerV2>(local.content).exactChoices.json().jsonObject
            replaceObservation(document(PostDraftControllerTest.draft(CLIENT, local.content.caption).json().jsonObject + choices), "\"1\"")
        }
        fun replaceObservation(document: WireDocument, etag: String) {
            val old = current(); val row = store.records.getValue(KEY)
            val local = old.locals.single { it.clientDraftId == CLIENT }
            // Synthetic stored observation, deliberately able to contradict a pinned historical
            // baseline. This is not an authorized import or an acknowledgement-producing path.
            val next = snapshots.create(local.clientDraftId, local.localRevision, local.content, DraftServerAssociationV1.Observed(document, etag))
            store.records[KEY] = PrivateRecord(row.revision + 1, 2,
                codec.encode(old.copy(locals = old.locals.map { if (it.clientDraftId == CLIENT) next else it })))
        }
        suspend fun review(root: String = CLIENT): DraftReviewPin = publication { use ->
            owner.readForPublication(use, root, current().locals.single { it.clientDraftId == root }.localRevision)
        }
        suspend fun prepare(use: DraftJournalUse, pin: DraftReviewPin): Pair<PublicationOriginalLinkV1, DraftMutationContribution> {
            val snapshot = owner.reviewSnapshot(use, pin)
            val body = publicationBody(snapshot)
            val link = links.create(binding, COMMAND, NOW, ApiCall("publishPost", body = PrivateBytes(body.encodeUtf8()), idempotencyKey = SecretText(COMMAND)),
                snapshot, publicationTarget(snapshot), disclosure)
            val estimate = owner.preflightHold(use, pin, link, reserved(link))
            requireConsistent(link, estimate)
            val contribution = owner.prepareHold(use, pin, link, reserved(link))
            requireConsistent(link, owner.publicationProjection(use, contribution))
            return link to contribution
        }
        private fun publicationBody(snapshot: DraftLocalSnapshotV1): WireDocument {
            val fields = when (val content = snapshot.content) {
                is DraftLocalContentV1.ComposerV2 -> content.exactChoices.json().jsonObject
                is DraftLocalContentV1.TextV1 -> buildJsonObject {
                    put("caption", content.caption); content.altText?.let { put("altText", it) }
                    put("mediaIds", JsonArray(emptyList())); put("audience", postSelfAudience())
                    put("keepOnPlate", false); put("allowRecipeSaves", false); put("saveDisclosureVersion", disclosure.version)
                }
            }
            val selected = fields.toMutableMap(); selected["clientDraftId"] = JsonPrimitive(snapshot.clientDraftId)
            (snapshot.serverAssociation as? DraftServerAssociationV1.Observed)?.let {
                selected["draftId"] = JsonPrimitive(postString(it.exactPostDraft, "id"))
                selected["draftVersion"] = it.exactPostDraft.json().jsonObject.getValue("version")
            }
            return document(JsonObject(selected))
        }
        private fun publicationTarget(snapshot: DraftLocalSnapshotV1): PublicationReviewTargetV1 =
            when (val association = snapshot.serverAssociation) {
                DraftServerAssociationV1.NotObserved -> PublicationReviewTargetV1.DirectLocal
                is DraftServerAssociationV1.Observed -> PublicationReviewTargetV1.SavedDraft(postString(association.exactPostDraft, "id"),
                    ExactPostVersion(postVersion(association.exactPostDraft)), association.etag, association.exactPostDraft)
            }
        private fun historical(link: PublicationOriginalLinkV1) = PublicationJournalV1(binding, NOW,
            listOf(link.commandId), listOf(PublicationHistoryEntryV1.PendingOriginal(link)))
        fun reserved(link: PublicationOriginalLinkV1) = publicationCodec.reservedPublishedBytes(historical(link))
        fun requireConsistent(link: PublicationOriginalLinkV1, projection: DraftPublicationProjectionV2) =
            cross.requireConsistent(historical(link), projection)
        suspend fun allocateOriginal(): Pair<PublicationAllocatedOriginal, PublicationOriginalLinkV1> = publication { use ->
            val pin = owner.readForPublication(use, CLIENT, current().locals.single { it.clientDraftId == CLIENT }.localRevision)
            val snapshot = owner.reviewSnapshot(use, pin); val permit = composition.composerPermit(borrower)
            val principal = principalAdmission.resolve(permit)
            val probeId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
            val probe = links.create(binding, probeId, NOW,
                ApiCall("publishPost", body = PrivateBytes(publicationBody(snapshot).encodeUtf8()), idempotencyKey = SecretText(probeId)),
                snapshot, publicationTarget(snapshot), disclosure)
            val capacity = reserved(probe)
            requireConsistent(probe, owner.preflightHold(use, pin, probe, capacity))
            val allocated = allocations.allocate(permit, principal, NOW, publicationBody(snapshot), snapshot,
                publicationTarget(snapshot), disclosure, probeId, capacity) { /* Synthetic non-ACK progress only. */ }
            allocated to allocations.requireOriginal(permit, owner, borrower, allocated)
        }
        suspend fun hold(root: String = CLIENT): PublicationOriginalLinkV1 = publication { use ->
            val pin = owner.readForPublication(use, root, current().locals.single { it.clientDraftId == root }.localRevision)
            val (link, contribution) = prepare(use, pin)
            commitDraftContribution(use, contribution); link
        }
        suspend fun terminal(link: PublicationOriginalLinkV1, commit: Boolean) = publication { use ->
            val contribution = owner.prepareTerminal(use, link, receipt(link))
            if (commit) commitDraftContribution(use, contribution)
        }
        suspend fun commitDraftContribution(use: DraftJournalUse, contribution: DraftMutationContribution) {
            activeContribution = contribution
            val result = mealValue(composition.store.commit(scope, owner.mutations(use, contribution)))
            owner.verifyPublicationReadback(use, contribution, result)
        }
        fun receipt(link: PublicationOriginalLinkV1): PostPublicationReceipt {
            val body = document(buildJsonObject {
                put("id", POST); put("version", 1); put("createdAt", TIME); put("updatedAt", TIME)
                put("author", buildJsonObject { put("userId", ACCOUNT); put("displayName", "Synthetic cook"); put("handle", "synthetic"); put("avatarMediaId", number(99)) })
                val expected = PostPublicationAdapter(publicationPolicy.maxResponseBytes).expectedSelection(link.originalIntentForComparison().call).json().jsonObject
                for (field in listOf("caption", "altText", "mediaIds", "attachment", "sourcePostId")) expected[field]?.let { put(field, it) }
                put("audience", JsonObject(expected.getValue("audience").jsonObject + ("bindings" to JsonArray(emptyList()))))
                put("status", "published"); put("publishedAt", TIME); put("expiresAt", "2026-09-15T12:00:00Z"); put("keepOnPlate", expected.getValue("keepOnPlate"))
                put("savePolicy", buildJsonObject { put("allowFutureSaves", false); put("policyVersion", 1); put("disclosureVersion", disclosure.version) })
                put("aclVersion", 1); put("capabilities", JsonArray(listOf(JsonPrimitive("view"), JsonPrimitive("delete")))); put("reactionCounts", JsonArray(emptyList()))
            })
            return PostPublicationAdapter(publicationPolicy.maxResponseBytes).receipt(link.originalIntentForComparison().call, ACCOUNT,
                ApiReply(201, PrivateBytes(body.encodeUtf8()), "\"1\"", contentType = "application/json"))
        }
        fun assertChoices(before: DraftLocalSnapshotV1, after: DraftLocalSnapshotV1) {
            val a = assertIs<DraftLocalContentV1.ComposerV2>(before.content); val b = assertIs<DraftLocalContentV1.ComposerV2>(after.content)
            assertEquals(a.exactChoices.json().jsonObject - setOf("caption", "altText"), b.exactChoices.json().jsonObject - setOf("caption", "altText"))
            assertEquals(a.historicalDisclosureText, b.historicalDisclosureText)
        }
        suspend fun close() { controller.close(); owner.release(participant); composition.release(borrower); saveDelivery.dispose(); boundary.clear() }
    }
    private companion object {
        const val ORIGIN = "00000000-0000-4000-8000-000000000081"
        const val CLIENT = "00000000-0000-4000-8000-000000000082"
        const val OTHER = "00000000-0000-4000-8000-000000000083"
        const val COMMAND = "00000000-0000-4000-8000-000000000084"
        const val POST = "00000000-0000-4000-8000-000000000085"
        const val ACCOUNT = "00000000-0000-4000-8000-000000000086"
        const val SOURCE = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        const val TIME = "2026-09-14T12:00:00Z"
        const val NOW = 1_800_000_000_000L
        val KEY = RecordKey("mealflow.post-drafts.v1", ORIGIN)
        fun <T> value(result: PortResult<T>) = assertIs<PortResult.Value<T>>(result).value
        fun PortResult<PostDraftState>.value() = value(this)
        fun failure(result: PortResult<*>, reason: FailureReason) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
    }
}
