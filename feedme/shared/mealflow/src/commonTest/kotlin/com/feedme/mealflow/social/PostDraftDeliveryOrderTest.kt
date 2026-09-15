package com.feedme.mealflow.social

import com.feedme.core.ports.*
import com.feedme.mealflow.*
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.CLIENT
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.KEY
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.ORIGIN
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.document
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.draft
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.failure
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.number
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.policy
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.response
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.value
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Real StateFlow collectors and actual controller/composition/queue with explicitly synthetic
 * transport/CAS storage. Ticket-only cases deliberately do not claim store/receipt provenance.
 * No native owner, provider, migration, principal mapping or publication is supplied here. */
@OptIn(ExperimentalCoroutinesApi::class)
class PostDraftDeliveryOrderTest {
    @Test fun authorizationAloneNeverPublishesOrMarksEitherFormatEditOrApply() = runTest {
        for (current in listOf(false, true)) for (apply in listOf(false, true)) {
            val f = TicketFixture(current, apply)
            val authorization = f.authorize()
            assertSame(f.before, f.flow.value); assertFalse(f.delivered())
            assertNull(f.ticket.publishAuthorized(DraftControllerDelivery.Authorization()))
            assertFalse(f.delivered()); assertSame(f.projected, f.ticket.publishAuthorized(authorization))
            assertTrue(f.delivered()); assertNull(f.ticket.publishAuthorized(authorization))
        }
    }

    @Test fun revocationBeforeAuthorizationCannotExposeEvenATransientProjectedState() = runTest {
        for (current in listOf(false, true)) for (apply in listOf(false, true)) {
            val f = TicketFixture(current, apply); val seen = mutableListOf<PostDraftState>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.flow.collect { seen += it } }
            try {
                f.ticket.revoke()
                assertNull(f.ticket.authorize(f.edit, f.apply, f.before, f.before, f.projected))
                assertFalse(seen.any { it === f.projected }); assertFalse(f.delivered()); assertSame(f.before, f.flow.value)
            } finally { collector.cancelAndJoin() }
        }
    }

    @Test fun navigationAfterAuthorizationBeforeCasAbandonsWithoutAckOrEvidenceDelivery() = runTest {
        for (current in listOf(false, true)) for (apply in listOf(false, true)) {
            val f = TicketFixture(current, apply); val seen = mutableListOf<PostDraftState>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.flow.collect { seen += it } }
            try {
                val authorization = f.authorize()
                f.ticket.revoke(); val redacted = PostDraftState.unavailable(); f.flow.value = redacted
                assertNull(f.ticket.publishAuthorized(authorization)); assertFalse(f.delivered())
                assertSame(redacted, f.flow.value); assertFalse(seen.any { it === f.projected })
                assertNull(f.ticket.publishAuthorized(authorization))
            } finally { collector.cancelAndJoin() }
        }
    }

    @Test fun cancellationAfterAuthorizationCannotRetroactivelyRevokeAnEarlierAuthorizedUnchangedView() = runTest {
        for (current in listOf(false, true)) for (apply in listOf(false, true)) {
            val f = TicketFixture(current, apply); val authorization = f.authorize()
            f.ticket.revoke() // Same cancellation operation; authorization already won its CAS.
            assertFalse(f.delivered()); assertSame(f.projected, f.ticket.publishAuthorized(authorization)); assertTrue(f.delivered())
        }
    }

    @Test fun synchronousCollectorRevocationAfterCasCannotEraseAlreadyObservedActualDelivery() = runTest {
        for (current in listOf(false, true)) for (apply in listOf(false, true)) {
            val f = TicketFixture(current, apply); var criticalPointObserved = false
            val redacted = PostDraftState.unavailable()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.flow.collect { value ->
                if (value === f.projected) {
                    // Assert the collector actually ran inside the CAS→held-stamp interval.
                    assertFalse(f.delivered()); criticalPointObserved = true
                    f.ticket.revoke(); f.flow.value = redacted
                }
            } }
            try {
                assertSame(f.projected, f.ticket.publishAuthorized(f.authorize()))
                assertTrue(criticalPointObserved); assertTrue(f.delivered()); assertSame(redacted, f.flow.value)
            } finally { collector.cancelAndJoin() }
        }
    }

    @Test fun explicitAbandonAndForeignAuthorizationCannotPublishOrRetireEvidence() = runTest {
        for (current in listOf(false, true)) for (apply in listOf(false, true)) {
            val first = TicketFixture(current, apply); val other = TicketFixture(current, apply)
            val authorization = first.authorize(); val foreign = other.authorize()
            first.ticket.abandon(foreign); assertFalse(first.delivered())
            assertNull(first.ticket.publishAuthorized(foreign)); first.ticket.abandon(authorization)
            assertNull(first.ticket.publishAuthorized(authorization)); assertSame(first.before, first.flow.value); assertFalse(first.delivered())
            assertSame(other.projected, other.ticket.publishAuthorized(foreign)); assertTrue(other.delivered())
        }
    }

    @Test fun actualLocalAckCollectorInvalidationPreservesTheEarlierPublishedAckInBothFormats() = runTest {
        for (current in listOf(false, true)) {
            val f = ControllerFixture(this, current); var armed = false; var observed = false; var proof: DraftControllerEdit? = null
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.controller.states.collect { state ->
                if (armed && state.selected?.localAcknowledged == true) {
                    proof = f.heldEdit(); assertNotNull(proof); assertFalse(proof!!.delivered())
                    observed = true; armed = false; f.boundary.clear()
                }
            } }
            try {
                armed = true
                val acknowledged = f.controller.newLocalDraft("Actual locally persisted text").value()
                assertTrue(observed); assertTrue(acknowledged.selected!!.localAcknowledged); assertTrue(proof!!.delivered())
                assertEquals(PostDraftPhase.UNAVAILABLE, f.controller.states.value.phase)
                assertEquals(1, f.ids); assertTrue(f.calls.isEmpty()); assertEquals(1, f.store.writes)
            } finally { collector.cancelAndJoin(); f.close() }
        }
    }

    @Test fun actualServerAckCollectorInvalidationPreservesExactAppliedArchiveWitnessInBothFormats() = runTest {
        for (current in listOf(false, true)) {
            val f = ControllerFixture(this, current); var observed = false; var proof: DraftControllerApply? = null
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.controller.states.collect { state ->
                if (state.serverAcknowledged) {
                    proof = f.heldApply(); assertNotNull(proof); assertFalse(proof!!.delivered()); assertNotNull(proof!!.archive)
                    observed = true; f.boundary.clear()
                }
            } }
            try {
                f.controller.newLocalDraft("Save this private draft").value()
                val acknowledged = f.controller.saveExplicitly().value()
                assertTrue(observed); assertTrue(acknowledged.serverAcknowledged); assertTrue(proof!!.delivered())
                assertEquals(PostDraftPhase.UNAVAILABLE, f.controller.states.value.phase)
                assertEquals(1, f.calls.size); assertEquals("createPostDraft", f.calls.single().operationId)
                assertTrue(f.store.records.keys.any { it.collection == "feedme.command.metadata" })
            } finally { collector.cancelAndJoin(); f.close() }
        }
    }

    @Test fun cancellationObservedByAckCollectorCannotTurnAnEmittedAckIntoUndeliveredEvidence() = runTest {
        for (current in listOf(false, true)) {
            val f = ControllerFixture(this, current); var caller: Job? = null; var observed = false; var proof: DraftControllerEdit? = null
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.controller.states.collect { state ->
                if (state.selected?.localAcknowledged == true) {
                    proof = f.heldEdit(); assertFalse(proof!!.delivered()); observed = true
                    caller!!.cancel(CancellationException("Cancelled after actual ACK emission"))
                }
            } }
            try {
                val task = async(start = CoroutineStart.LAZY) { f.controller.newLocalDraft("Already authorized") }
                caller = task; task.start(); task.join()
                assertTrue(observed); assertTrue(task.isCancelled); assertTrue(proof!!.delivered())
                assertTrue(f.controller.states.value.selected!!.localAcknowledged)
                // Coroutine cancellation may suppress the returned Deferred value after the
                // no-await tail. It cannot retract an ACK genuinely seen by the collector.
            } finally { collector.cancelAndJoin(); f.close() }
        }
    }

    @Test fun realCallerCancellationAndBackBeforeAuthorizationNeverEmitLocalAck() = runTest {
        for (current in listOf(false, true)) for (cancel in listOf(false, true)) {
            val f = ControllerFixture(this, current); val scheduled = StandardTestDispatcher(testScheduler)
            val paused = ArrayDeque<Pair<CoroutineContext, Runnable>>(); var hold = false; var emitted = false
            val caller = object : CoroutineDispatcher() { override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (hold) paused.addLast(context to block) else scheduled.dispatch(context, block)
            } }
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.controller.states.collect {
                if (it.selected?.localAcknowledged == true) emitted = true
            } }
            val task = async(caller) { hold = true; f.controller.newLocalDraft("Unreturned original") }
            try {
                runCurrent(); assertEquals(1, paused.size)
                // newLocalDraft has no editFields staging hop: release the operation return,
                // then hold its armed caller tail before authorization or ACK emission.
                val next = paused.removeFirst(); scheduled.dispatch(next.first, next.second); runCurrent()
                assertEquals(1, paused.size); val proof = assertNotNull(f.heldEdit()); assertFalse(proof.delivered())
                assertNotNull(proof.legacy?.delivery ?: proof.current?.delivery); assertFalse(emitted)
                if (cancel) task.cancel() else f.controller.back().value()
                hold = false; while (paused.isNotEmpty()) { val next = paused.removeFirst(); scheduled.dispatch(next.first, next.second) }
                if (cancel) { task.join(); assertTrue(task.isCancelled) } else failure(task.await(), FailureReason.STALE_SESSION)
                assertFalse(emitted); assertFalse(proof.delivered()); assertTrue(f.calls.isEmpty()); assertEquals(1, f.ids)
            } finally {
                hold = false; while (paused.isNotEmpty()) { val next = paused.removeFirst(); scheduled.dispatch(next.first, next.second) }
                withContext(NonCancellable) { task.cancelAndJoin(); collector.cancelAndJoin(); f.close() }
            }
        }
    }

    private class TicketFixture(useCurrent: Boolean, useApply: Boolean) {
        val before = PostDraftState.empty(); val projected = PostDraftState.empty(); val flow = MutableStateFlow(before)
        val ticket = DraftControllerDelivery()
        val snapshots = DraftLocalSnapshotCodecV1(policy(), publicationPolicy())
        val edit = if (useApply) null else if (useCurrent) DraftControllerEdit(PostDraftCurrentEdit(snapshots.create(CLIENT, 1,
            DraftLocalContentV1.TextV1("Exact", null), DraftServerAssociationV1.NotObserved), listOf(null)), snapshots)
            else DraftControllerEdit(PostEdit(PostLocal(CLIENT, 1, "Exact", null), listOf(null)))
        private val original = PostOriginal(number(90), "createPostDraft", CLIENT, 1,
            com.feedme.contracts.WireDocument.parse("{\"caption\":\"Exact\"}"), null, null, 1)
        private val mutation = StoreMutation.Put(KEY, 1, if (useCurrent) 2 else 1, PrivateBytes(byteArrayOf(1)))
        val apply = if (!useApply) null else if (useCurrent) DraftControllerApply(PostDraftCurrentApply(PostDraftCommandV2.TextCreate(original), mutation, 2, false))
            else DraftControllerApply(PostApply(original, mutation, 2, false))
        init { assertTrue(ticket.arm(edit, apply, before, flow)); ticket.retain(edit, apply) }
        fun authorize() = assertNotNull(ticket.authorize(edit, apply, before, before, projected))
        fun delivered() = edit?.delivered() ?: apply!!.delivered()
    }
    private class ControllerFixture(test: TestScope, val current: Boolean) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val boundary = SessionBoundary(); val scope = StorageScope("delivery-order-test", ActorKind.ACCOUNT, "synthetic actor")
        val lease = boundary.activate(scope); val store = PostDraftControllerTest.Store(scope)
        var ids = 0; val calls = mutableListOf<ApiCall>()
        val access = AuthenticatedMealPlanningAccess(lease, ORIGIN, store, object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                assertSame(this@ControllerFixture.lease, lease); calls += call
                assertEquals("createPostDraft", call.operationId)
                val body = call.document().json().jsonObject
                return response(document(draft(body.getValue("clientDraftId").jsonPrimitive.content).json().jsonObject + body), 201)
            }
        }, true)
        val composition = MealKitchenComposition(access, boundary, dispatcher, EpochClock { 1_000_000 }, ConnectivityPort { Connectivity.ONLINE })
        val draftPolicy = policy()
        val controller = PostDraftController(composition, MealOperationIds { number(100 + ++ids) }, draftPolicy,
            PostDraftJournalOwner(composition, draftPolicy, publicationPolicy().takeIf { current }))
        val snapshots = DraftLocalSnapshotCodecV1(policy(), publicationPolicy())
        fun heldEdit() = if (current) PostDraftCurrentHeld.edit(access, boundary)?.let { DraftControllerEdit(it, snapshots) }
            else PostDraftHeld.edit(access, boundary)?.let(::DraftControllerEdit)
        fun heldApply() = if (current) PostDraftCurrentHeld.apply(access, boundary)?.let(::DraftControllerApply)
            else PostDraftHeld.apply(access, boundary)?.let(::DraftControllerApply)
        suspend fun close() { controller.close(); boundary.clear() }
    }
    private companion object {
        fun publicationPolicy() = PostPublicationClientPolicy(1_048_576, 65_536, 8192, 64, 64, 8, 32, 32, 32, 65_536, 8192, 60_000)
    }
}
