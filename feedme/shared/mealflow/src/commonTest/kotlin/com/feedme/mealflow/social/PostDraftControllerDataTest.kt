package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.MealFailure
import com.feedme.mealflow.json
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.CLIENT
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.KEY
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.ORIGIN
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.SERVER
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.document
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.draft
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.number
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.policy
import kotlinx.serialization.json.*
import kotlin.test.*

/** Pure tagged-view/reference tests. The actual owner still admits only its own current-use
 * entry, mutation and proof; none of these wrappers constitutes admission or live consent. */
class PostDraftControllerDataTest {
    @Test fun currentEntryViewReturnsExactOwnerEntryNotAReconstructedLookalike() {
        val raw = PrivateRecord(9, 2, PrivateBytes("historical-only".encodeToByteArray()))
        val original = PostDraftV2Entry(raw, PostDraftV2Record(10))
        val view = DraftControllerEntry(raw, DraftControllerRecord(original.value, snapshots), original)
        assertSame(original, view.current())
        assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> {
            DraftControllerEntry(raw, DraftControllerRecord(original.value, snapshots)).current()
        }.reason)
        assertFailsWith<MealFailure> {
            DraftControllerEntry(raw, DraftControllerRecord(original.value.copy(), snapshots), original).current()
        }
    }
    @Test fun taggedRecordCannotAcceptLocalOrCommandFromTheOtherFormat() {
        val current = DraftControllerRecord(PostDraftV2Record(1), snapshots)
        val legacy = DraftControllerRecord(PostRecord(1))
        val a = current.newLocal(CLIENT, 1, "A", null); val b = legacy.newLocal(CLIENT, 1, "B", null)
        assertFailsWith<MealFailure> { current.copy(locals = listOf(b)) }
        assertFailsWith<MealFailure> { legacy.copy(locals = listOf(a)) }
        val original = PostOriginal(number(90), "createPostDraft", CLIENT, 1, null, null, null, 1)
        assertFailsWith<MealFailure> { current.copy(command = legacy.original(original)) }
        assertFailsWith<MealFailure> { legacy.copy(command = current.original(original)) }
    }
    @Test fun currentRecordCopyRetainsHoldTerminalAndFullRemainderWithoutReencodingThem() {
        val link = link(); val remainder = PostDraftRemainderV2(link, snapshots.create(CLIENT, 2,
            DraftLocalContentV1.TextV1("Never drop", ""), DraftServerAssociationV1.NotObserved))
        val terminal = PostDraftTerminalV2.Published(link, number(91))
        val hold = PostDraftPublicationHoldV2(link, PublicationCapacityReservationV1(8192, 12, 34, 1))
        // Deliberately contradictory pure data; the real codec refuses this combination. A
        // wrapper cannot repair it, flatten it into schema1 or make the held numbers authority.
        val row = PostDraftV2Record(10, publicationHold = hold, terminals = listOf(terminal), remainders = listOf(remainder))
        val copied = DraftControllerRecord(row, snapshots).copy(clock = 11).current!!
        assertSame(hold, copied.publicationHold); assertSame(terminal, copied.terminals.single()); assertSame(remainder, copied.remainders.single())
        assertEquals("Never drop", copied.remainders.single().content.caption)
    }
    @Test fun legacyOriginalAndCompletionRemainActualLegacyObjectsInsideViews() {
        val original = PostOriginal(number(90), "updatePostDraft", CLIENT, 1, WireDocument.parse(" { \"caption\" : \"A\" } \n"), draft(CLIENT), "\"1\"", 7)
        val completion = PostCompletion(original.id, original.operation, CLIENT, false)
        val view = DraftControllerRecord(PostRecord(10, command = original, completion = completion))
        val copied = view.copy(clock = 11)
        assertSame(original, copied.legacy!!.command); assertSame(completion, copied.legacy!!.completion)
        assertContentEquals(original.body!!.encodeUtf8(), copied.command!!.call().body!!.copyForCodec())
    }
    @Test fun currentTextAbsentEmptyAndPresentRemainDistinctAndRequireExactRevisionAdvance() {
        for (alt in listOf<String?>(null, "", "Description")) {
            val input = snapshots.create(CLIENT, 1, DraftLocalContentV1.TextV1("A", alt), DraftServerAssociationV1.NotObserved)
            val changed = DraftControllerLocal(input, snapshots).copy(revision = 2, caption = "B")
            assertEquals(alt, changed.alt); assertEquals(2L, changed.revision)
            assertFalse(changed.same(DraftControllerLocal(input, snapshots)))
            assertFailsWith<MealFailure> { changed.copy(revision = 2, caption = "Changed without revision") }
        }
    }
    @Test fun serverObservationCopyKeepsExactComposerContentAndLogicalRevision() {
        val choices = write().json().jsonObject - "clientDraftId"
        val input = snapshots.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(document(choices), "Actual disclosure"), DraftServerAssociationV1.NotObserved)
        val after = DraftControllerLocal(input, snapshots).copy(server = draft(CLIENT), etag = "\"1\"")
        assertEquals(3L, after.revision); assertEquals(input.content.caption, after.caption); assertTrue(after.requiresReviewedSave)
        assertContentEquals(assertIs<DraftLocalContentV1.ComposerV2>(input.content).exactChoices.encodeUtf8(),
            assertIs<DraftLocalContentV1.ComposerV2>(after.current!!.content).exactChoices.encodeUtf8())
        assertFailsWith<MealFailure> { after.copy(server = null, etag = null) }
    }
    @Test fun wrapperEqualityChecksFullSnapshotNotOnlyTextRevision() {
        val a = snapshots.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(document(write().json().jsonObject - "clientDraftId"), "Actual disclosure"), DraftServerAssociationV1.NotObserved)
        val b = snapshots.create(CLIENT, 3, DraftLocalContentV1.ComposerV2(document(write().json().jsonObject - "clientDraftId" + ("keepOnPlate" to JsonPrimitive(true))), "Actual disclosure"), DraftServerAssociationV1.NotObserved)
        val first = DraftControllerLocal(a, snapshots); val other = DraftControllerLocal(b, snapshots)
        assertEquals(first.caption, other.caption); assertEquals(first.revision, other.revision); assertFalse(first.same(other))
        assertTrue(first.same(DraftControllerLocal(snapshots.decode(a.exactUtf8), snapshots)))
    }
    @Test fun recreatedWitnessWrappersMatchOnlyTheirUnderlyingExactProof() {
        val snapshot = snapshots.create(CLIENT, 1, DraftLocalContentV1.TextV1("A", null), DraftServerAssociationV1.NotObserved)
        val proof = PostDraftCurrentEdit(snapshot, listOf(null))
        val a = DraftControllerEdit(proof, snapshots); val b = DraftControllerEdit(proof, snapshots)
        assertTrue(a.sameIdentity(b)); assertFalse(a.sameIdentity(DraftControllerEdit(PostDraftCurrentEdit(snapshot, listOf(null)), snapshots)))
        val legacy = PostEdit(PostLocal(CLIENT, 1, "A", null), listOf(null))
        assertTrue(DraftControllerEdit(legacy).sameIdentity(DraftControllerEdit(legacy)))
        assertFalse(a.sameIdentity(DraftControllerEdit(legacy)))
    }
    @Test fun deliveryDoesNotMixLegacyEditWithCurrentApply() {
        val edit = DraftControllerEdit(PostEdit(PostLocal(CLIENT, 1, "A", null), listOf(null)))
        val raw = PostOriginal(number(90), "deletePostDraft", CLIENT, 1, null, null, null, 1)
        val apply = DraftControllerApply(PostDraftCurrentApply(PostDraftCommandV2.Discard(raw),
            StoreMutation.Put(KEY, 1, 2, PrivateBytes(byteArrayOf(1))), 2, false))
        assertFalse(DraftControllerDelivery().arm(edit, apply, Any(), kotlinx.coroutines.flow.MutableStateFlow(PostDraftState.empty())))
    }
    @Test fun typedDeliveryRetainsActualCurrentWitnessAndNeverArmsLegacyProof() {
        val proof = PostDraftCurrentEdit(snapshots.create(CLIENT, 1, DraftLocalContentV1.TextV1("A", null), DraftServerAssociationV1.NotObserved), listOf(null))
        val edit = DraftControllerEdit(proof, snapshots); val ticket = DraftControllerDelivery(); val state = PostDraftState.empty()
        val projected = PostDraftState.empty(); val flow = kotlinx.coroutines.flow.MutableStateFlow(state)
        assertTrue(ticket.arm(edit, null, state, flow)); ticket.retain(edit, null)
        val authorization = assertNotNull(ticket.authorize(edit, null, state, state, projected))
        assertFalse(edit.delivered()); assertSame(projected, ticket.publishAuthorized(authorization)); assertTrue(edit.delivered())
        assertTrue(proof.delivery!!.delivered(proof)); assertNull(edit.legacy)
    }
    @Test fun completionCannotRetireAnotherReviewedCreationTimeOrExactOriginal() {
        val baseline = draft(CLIENT, "A"); val body = PrivateBytes(" {\"caption\":\"A\"} ".encodeToByteArray())
        val local = snapshots.create(CLIENT, 3, DraftLocalContentV1.TextV1("A", null), DraftServerAssociationV1.Observed(baseline, "\"1\""))
        val codec = PostDraftV2Codec("synthetic", ORIGIN, policy(), publication, snapshots, links)
        fun original(created: Long) = codec.createReviewedCommand(ReviewedDraftOriginalFieldsV2(number(90), CLIENT, 3,
            mapOf("draftId" to SERVER), body, baseline, "\"1\"", created), local, null)
        val before = original(7); val after = original(8)
        val proof = DraftControllerApply(PostDraftCurrentApply(before, StoreMutation.Put(KEY, 1, 2, PrivateBytes(byteArrayOf(1))), 2, true))
        assertTrue(DraftControllerCompletion(PostDraftCompletionV2.ReviewedUnsent(before)).matches(proof))
        assertFalse(DraftControllerCompletion(PostDraftCompletionV2.ReviewedUnsent(after)).matches(proof))
    }
    @Test fun newDisplaySummariesAreDetachedHistoricalAndNeverEditableOrContentBearing() {
        val hold = PostDraftPublicationHold(CLIENT, number(90), 3, true)
        val remainder = PostDraftRemainderSummary(CLIENT, number(90), 3, 4, true)
        assertTrue(hold.historical); assertTrue(remainder.historical); assertFalse(remainder.editable)
        val source = mutableListOf(remainder)
        val state = PostDraftState(PostDraftScreen.LOCAL_LIST, PostDraftPhase.READY, emptyList(), null, emptyList(),
            false, null, true, false, null, null, false, PostDraftIssue.NONE, publicationHold = hold, unsubmittedRemainders = source)
        source.clear(); assertSame(remainder, state.unsubmittedRemainders.single())
        assertFalse(hold.toString().contains(CLIENT)); assertFalse(remainder.toString().contains(CLIENT))
    }
    private companion object {
        val publication = PostPublicationClientPolicy(1_048_576, 65_536, 8192, 64, 64, 8, 32, 32, 32, 65_536, 8192, 60_000)
        val snapshots = DraftLocalSnapshotCodecV1(policy(), publication)
        val links = PublicationOriginalLinkCodecV1(publication, snapshots)
        fun write() = document(mapOf("clientDraftId" to JsonPrimitive(CLIENT), "caption" to JsonPrimitive("A"),
            "mediaIds" to JsonArray(emptyList()), "audience" to postSelfAudience(), "keepOnPlate" to JsonPrimitive(false),
            "allowRecipeSaves" to JsonPrimitive(false), "saveDisclosureVersion" to JsonPrimitive("v1")))
        fun link(): PublicationOriginalLinkV1 {
            val local = snapshots.create(CLIENT, 1, DraftLocalContentV1.TextV1("A", null), DraftServerAssociationV1.NotObserved)
            return links.create(PublicationJournalBindingData("synthetic", ORIGIN, ORIGIN, number(88)), number(90), 7,
                ApiCall("publishPost", body = PrivateBytes(write().encodeUtf8()), idempotencyKey = SecretText(number(90))), local,
                PublicationReviewTargetV1.DirectLocal, PublicationDisclosure("v1", "Actual disclosure"))
        }
    }
}
