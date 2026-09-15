package com.feedme.mealflow.social

import com.feedme.core.ports.*
import com.feedme.mealflow.AuthenticatedMealPlanningAccess
import com.feedme.mealflow.MealFailure
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.CLIENT
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.KEY
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.ORIGIN
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.number
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.policy
import kotlin.test.*

/** Pure reference/lifetime mechanics using explicitly synthetic ports. These are not commit,
 * archive, transport, authority or final controller caller-return acceptance tests. */
class PostDraftCurrentHeldTest {
    @Test fun editCopiesPredecessorsAndRetainsCompleteSnapshotBytes() {
        val values = mutableListOf<DraftLocalSnapshotV1?>(snapshot())
        val proposed = snapshot(2, "New")
        val edit = PostDraftCurrentEdit(proposed, values)
        values.clear(); assertEquals(1, edit.predecessors.size)
        val second = snapshot(3, "Other")
        val mutable = mutableListOf<DraftLocalSnapshotV1?>(proposed, second)
        edit.predecessors = mutable; mutable.clear()
        assertEquals(2, edit.predecessors.size)
        assertSame(proposed, edit.proposed)
        assertContentEquals(proposed.exactUtf8.copyForCodec(), edit.proposed.exactUtf8.copyForCodec())
    }
    @Test fun predecessorOverflowAndEmptyWitnessAreRefusedWithoutChangingPreviousEvidence() {
        val edit = PostDraftCurrentEdit(snapshot(), listOf(null))
        assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> { edit.predecessors = emptyList() }.reason)
        assertEquals(FailureReason.CONFLICT, assertFailsWith<MealFailure> { edit.predecessors = List(3) { null } }.reason)
        assertEquals(listOf(null), edit.predecessors)
    }
    @Test fun constructingEditOrApplyNeverImpliesDeliveryOrArchive() {
        val edit = PostDraftCurrentEdit(snapshot(), listOf(null)); val apply = apply()
        assertNull(edit.delivery); assertNull(edit.mutation)
        assertNull(apply.delivery); assertNull(apply.archive)
        assertFalse(PostDraftCurrentDelivery().delivered(edit)); assertFalse(PostDraftCurrentDelivery().delivered(apply))
    }
    @Test fun deliveryRequiresExactEditAndPublicationReferences() {
        val edit = PostDraftCurrentEdit(snapshot(), listOf(null)); val other = PostDraftCurrentEdit(edit.proposed, listOf(null))
        val ticket = PostDraftCurrentDelivery(); val publication = Any()
        assertTrue(ticket.arm(edit, null, publication))
        assertFalse(ticket.deliver(other, null, publication)); assertFalse(ticket.deliver(edit, null, Any()))
        assertFalse(ticket.delivered(edit)); assertTrue(ticket.deliver(edit, null, publication))
        assertTrue(ticket.delivered(edit)); assertFalse(ticket.delivered(other))
    }
    @Test fun deliveryRequiresExactApplyReferenceEvenForByteIdenticalOriginalAndMutation() {
        val first = apply(); val other = PostDraftCurrentApply(first.original, first.mutation, first.receiptRevision, first.unsent)
        val ticket = PostDraftCurrentDelivery(); val publication = Any()
        assertTrue(ticket.arm(null, first, publication)); assertFalse(ticket.deliver(null, other, publication))
        assertTrue(ticket.deliver(null, first, publication)); assertTrue(ticket.delivered(first)); assertFalse(ticket.delivered(other))
    }
    @Test fun ticketCannotBeRearmedOrDeliveredTwice() {
        val edit = PostDraftCurrentEdit(snapshot(), listOf(null)); val ticket = PostDraftCurrentDelivery(); val state = Any()
        assertTrue(ticket.arm(edit, null, state)); assertFalse(ticket.arm(edit, null, state))
        assertTrue(ticket.deliver(edit, null, state)); assertFalse(ticket.deliver(edit, null, state))
    }
    @Test fun revocationBeforeOrAfterArmingPreventsDelivery() {
        for (armed in listOf(false, true)) {
            val edit = PostDraftCurrentEdit(snapshot(), listOf(null)); val ticket = PostDraftCurrentDelivery(); val state = Any()
            if (armed) assertTrue(ticket.arm(edit, null, state))
            ticket.revoke(); assertFalse(ticket.arm(edit, null, state)); assertFalse(ticket.deliver(edit, null, state))
            assertFalse(ticket.delivered(edit))
        }
    }
    @Test fun revocationAfterActualDeliveryDoesNotRevokePastDelivery() {
        val edit = PostDraftCurrentEdit(snapshot(), listOf(null)); val ticket = PostDraftCurrentDelivery(); val state = Any()
        assertTrue(ticket.arm(edit, null, state)); assertTrue(ticket.deliver(edit, null, state)); ticket.revoke()
        assertTrue(ticket.delivered(edit))
    }
    @Test fun registryRetainsExactWitnessAcrossAccessWrapperReplacementOnSameLease() {
        val f = Fixture()
        try {
            val edit = PostDraftCurrentEdit(snapshot(), listOf(null)); val apply = apply()
            PostDraftCurrentHeld.retainEdit(f.access, f.boundary, edit); PostDraftCurrentHeld.retainApply(f.access, f.boundary, apply)
            val wrapper = f.access(f.store, ORIGIN)
            assertSame(edit, PostDraftCurrentHeld.edit(wrapper, f.boundary)); assertSame(apply, PostDraftCurrentHeld.apply(wrapper, f.boundary))
            assertEquals(0, f.io)
        } finally { f.boundary.clear() }
    }
    @Test fun wrongStoreBoundaryAndRawOriginCannotObtainHeldWitness() {
        val f = Fixture()
        try {
            PostDraftCurrentHeld.retainEdit(f.access, f.boundary, PostDraftCurrentEdit(snapshot(), listOf(null)))
            assertNull(PostDraftCurrentHeld.edit(f.access(f.otherStore, ORIGIN), f.boundary))
            assertNull(PostDraftCurrentHeld.edit(f.access(f.store, number(88)), f.boundary))
            assertNull(PostDraftCurrentHeld.edit(f.access, SessionBoundary()))
            assertEquals(0, f.io)
        } finally { f.boundary.clear() }
    }
    @Test fun oldWitnessCannotClearItsReplacement() {
        val f = Fixture()
        try {
            val old = PostDraftCurrentEdit(snapshot(), listOf(null)); val next = PostDraftCurrentEdit(snapshot(2), listOf(snapshot()))
            PostDraftCurrentHeld.retainEdit(f.access, f.boundary, old); PostDraftCurrentHeld.retainEdit(f.access, f.boundary, next)
            PostDraftCurrentHeld.clearEdit(f.access, f.boundary, old); assertSame(next, PostDraftCurrentHeld.edit(f.access, f.boundary))
            PostDraftCurrentHeld.clearEdit(f.access, f.boundary, next); assertNull(PostDraftCurrentHeld.edit(f.access, f.boundary))
        } finally { f.boundary.clear() }
    }
    @Test fun actualLeaseInvalidationClearsCurrentEvidenceWithoutTouchingOtherBoundary() {
        val f = Fixture(); val other = Fixture()
        try {
            val value = PostDraftCurrentEdit(snapshot(), listOf(null))
            PostDraftCurrentHeld.retainEdit(f.access, f.boundary, value); PostDraftCurrentHeld.retainEdit(other.access, other.boundary, value)
            f.boundary.clear()
            assertNull(PostDraftCurrentHeld.edit(f.access, f.boundary)); assertSame(value, PostDraftCurrentHeld.edit(other.access, other.boundary))
            assertEquals(FailureReason.STALE_SESSION, assertFailsWith<MealFailure> { PostDraftCurrentHeld.retainEdit(f.access, f.boundary, value) }.reason)
        } finally { f.boundary.clear(); other.boundary.clear() }
    }
    @Test fun currentAndLegacyProofRegistriesDoNotConvertEachOthersEvidence() {
        val f = Fixture()
        try {
            val old = PostEdit(PostLocal(CLIENT, 1, "Legacy", null), listOf(null))
            val current = PostDraftCurrentEdit(snapshot(), listOf(null))
            PostDraftHeld.retainEdit(f.access, f.boundary, old); PostDraftCurrentHeld.retainEdit(f.access, f.boundary, current)
            assertSame(old, PostDraftHeld.edit(f.access, f.boundary)); assertSame(current, PostDraftCurrentHeld.edit(f.access, f.boundary))
            assertNull(old.delivery); assertNull(current.delivery)
        } finally { f.boundary.clear() }
    }
    @Test fun evidenceDiagnosticsAreContentFree() {
        val edit = PostDraftCurrentEdit(snapshot(caption = "Never log me"), listOf(null)); val apply = apply()
        assertFalse(edit.toString().contains("Never log me")); assertFalse(apply.toString().contains(CLIENT))
        assertFalse(edit.toString().contains(CLIENT))
    }
    private class Fixture {
        val scope = StorageScope("current-proof-test", ActorKind.ACCOUNT, "synthetic arbitrary actor")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope); var io = 0
        private fun port() = object : PrivateStateStore {
            override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> { io++; return PortResult.Failure(FailureReason.UNAVAILABLE) }
            override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> { io++; return PortResult.Failure(FailureReason.UNAVAILABLE) }
            override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> { io++; return PortResult.Failure(FailureReason.FORBIDDEN) }
        }
        val store = port(); val otherStore = port()
        fun access(store: PrivateStateStore, origin: String) = AuthenticatedMealPlanningAccess(lease, origin, store, object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> { io++; return PortResult.Failure(FailureReason.UNAVAILABLE) }
        }, true)
        val access = access(store, ORIGIN)
    }
    private companion object {
        val publication = PostPublicationClientPolicy(1_048_576, 65_536, 8192, 64, 64, 8, 32, 32, 32, 65_536, 8192, 60_000)
        val snapshots = DraftLocalSnapshotCodecV1(policy(), publication)
        fun snapshot(revision: Long = 1, caption: String = "Caption") = snapshots.create(CLIENT, revision,
            DraftLocalContentV1.TextV1(caption, null), DraftServerAssociationV1.NotObserved)
        fun apply(): PostDraftCurrentApply {
            val original = PostOriginal(number(90), "deletePostDraft", CLIENT, 1, null, null, null, 1)
            // Deliberately data-only: malformed originals are not rejected/made valid by a
            // reference ticket; the real owner/codec/controller must validate before use.
            return PostDraftCurrentApply(PostDraftCommandV2.Discard(original), StoreMutation.Put(KEY, 1, 2, PrivateBytes(byteArrayOf(1))), 2, false)
        }
    }
}
