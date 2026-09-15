package com.feedme.mealflow.social

import com.feedme.core.ports.*
import com.feedme.mealflow.*
import com.feedme.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Actual legacy controller/owner/composition over synthetic CAS/transport. Publication members
 * exercise claim lifetime only; this is not a publication coordinator or provider proof. */
@OptIn(ExperimentalCoroutinesApi::class)
class PostDraftJournalOwnerTest {
    @Test fun constructionAndRegistrationDoNotClaimReadWriteOrInvokeControllerReadiness() = runTest {
        val f = Harness(this); var checks = 0
        try {
            val draft = f.member(MealKitchenFeature.POST_DRAFTS, check = { checks++ })
            f.member(MealKitchenFeature.POST_PUBLICATIONS, check = { checks++ })
            assertEquals(0, checks); f.noIo()
            // Another owner can still claim before this owner has ever entered an operation.
            val other = PostDraftJournalOwner(f.composition, f.policy)
            val participant = other.register(draft.borrower)
            f.composition.operate(draft.borrower) {
                val use = other.enter(f.composition.composerPermit(draft.borrower), participant)
                other.leave(use)
            }
            other.release(participant); assertTrue(checks > 0); f.noIo()
        } finally { f.close() }
    }

    @Test fun registrationRejectsForeignFabricatedAndReleasedMembersWithoutIo() = runTest {
        val f = Harness(this); val foreign = Harness(this)
        try {
            val actual = f.bind(MealKitchenFeature.POST_DRAFTS)
            val fabricated = MealKitchenComposition.Borrower(f.composition, actual.feature, actual.hooks)
            failure(FailureReason.STALE_SESSION) { f.journal.register(fabricated) }
            val wrongOwner = foreign.bind(MealKitchenFeature.POST_DRAFTS)
            failure(FailureReason.STALE_SESSION) { f.journal.register(wrongOwner) }
            f.bind(MealKitchenFeature.COOKING) // Keep the composition live after draft release.
            f.composition.release(actual)
            failure(FailureReason.STALE_SESSION) { f.journal.register(actual) }
            f.noIo(); foreign.noIo()
        } finally { f.close(); foreign.close() }
    }

    @Test fun registrationAdmitsOnlySocialPurposesAndGuestNeverEntersAClaim() = runTest {
        val f = Harness(this); val guest = Harness(this, Shared(ActorKind.GUEST))
        try {
            for (feature in listOf(MealKitchenFeature.COOKING, MealKitchenFeature.COOKBOOK))
                failure(FailureReason.NOT_CONFIGURED) { f.journal.register(f.bind(feature)) }
            val member = guest.member(MealKitchenFeature.POST_DRAFTS)
            failure(FailureReason.UNAUTHENTICATED) { guest.use(member) { error("Guest use must not start") } }
            f.noIo(); guest.noIo()
        } finally { f.close(); guest.close() }
    }

    @Test fun duplicateAndLateRegistrationCannotExpandTheClaimLifetime() = runTest {
        val f = Harness(this)
        try {
            val member = f.member(MealKitchenFeature.POST_DRAFTS)
            failure(FailureReason.CONFLICT) { f.journal.register(member.borrower) }
            val publication = f.bind(MealKitchenFeature.POST_PUBLICATIONS)
            f.use(member) {}
            failure(FailureReason.CONFLICT) { f.journal.register(publication) }
            f.noIo()
        } finally { f.close() }
    }

    @Test fun forgedParticipantUseAndCrossPurposePermitCannotAcquireNamespace() = runTest {
        val f = Harness(this)
        try {
            val draft = f.member(MealKitchenFeature.POST_DRAFTS)
            val publication = f.member(MealKitchenFeature.POST_PUBLICATIONS)
            f.composition.operate(draft.borrower) {
                val permit = f.composition.composerPermit(draft.borrower)
                failure(FailureReason.STALE_SESSION) { f.journal.enter(permit, DraftJournalParticipant()) }
                failure(FailureReason.STALE_SESSION) { f.journal.enter(permit, publication.participant) }
                failure(FailureReason.STALE_SESSION) { f.journal.readLegacy(DraftJournalUse()) }
                val use = f.journal.enter(permit, draft.participant)
                try {
                    failure(FailureReason.CONFLICT) { f.journal.enter(permit, draft.participant) }
                    failure(FailureReason.STALE_SESSION) { f.journal.leave(DraftJournalUse()) }
                    failure(FailureReason.STALE_SESSION) { f.journal.release(DraftJournalParticipant()) }
                } finally { f.journal.leave(use) }
            }
            f.noIo()
        } finally { f.close() }
    }

    @Test fun oldUseCannotSurviveLeaveOrBorrowTheNextOperationWhileNestedUseRemainsValid() = runTest {
        val f = Harness(this); val member = f.member(MealKitchenFeature.POST_DRAFTS)
        lateinit var old: DraftJournalUse
        try {
            f.use(member) { use ->
                old = use
                withContext(f.dispatcher) { f.journal.preflightLegacy(use, PostRecord(NOW)) }
                coroutineScope { async { f.journal.preflightLegacy(use, PostRecord(NOW)) }.await() }
            }
            failure(FailureReason.STALE_SESSION) { f.journal.readLegacy(old) }
            f.use(member) { current ->
                failure(FailureReason.STALE_SESSION) { f.journal.readLegacy(old) }
                f.journal.preflightLegacy(current, PostRecord(NOW))
            }
            f.noIo()
        } finally { f.close() }
    }

    @Test fun oneOwnerSharesClaimBetweenParticipantsAndPublicationCannotReadOrWriteLegacyRecords() = runTest {
        val f = Harness(this); val draft = f.member(MealKitchenFeature.POST_DRAFTS)
        val publication = f.member(MealKitchenFeature.POST_PUBLICATIONS)
        try {
            f.use(draft) { use ->
                val entry = f.journal.readLegacy(use)
                f.journal.commitLegacy(use, f.journal.mutationLegacy(use, entry, record()))
            }
            val before = f.shared.store.records.getValue(KEY); val reads = f.shared.store.reads; val writes = f.shared.store.writes
            f.use(publication) { use ->
                failure(FailureReason.NOT_CONFIGURED) { f.journal.readLegacy(use) }
                failure(FailureReason.NOT_CONFIGURED) { f.journal.preflightLegacy(use, record()) }
                failure(FailureReason.NOT_CONFIGURED) { f.journal.schemaLegacy(use, "PostDraftPatch", PostDraftControllerTest.document(emptyMap())) }
                failure(FailureReason.NOT_CONFIGURED) { f.journal.mutationLegacy(use, PostEntry(before, record()), record()) }
                failure(FailureReason.NOT_CONFIGURED) { f.journal.refreshLegacy(use, before) }
                failure(FailureReason.NOT_CONFIGURED) { f.journal.commitLegacy(use, StoreMutation.Put(KEY, before.revision, 1, before.payload)) }
            }
            assertEquals(reads, f.shared.store.reads); assertEquals(writes, f.shared.store.writes)
            assertContentEquals(before.payload.copyForCodec(), f.shared.store.records.getValue(KEY).payload.copyForCodec())
            assertTrue(f.shared.calls.isEmpty()); assertEquals(0, f.shared.store.erases)
        } finally { f.close() }
    }

    @Test fun releasingOneParticipantCannotReleaseSiblingClaimAndFinalDetachCan() = runTest {
        val shared = Shared(); val f = Harness(this, shared); val peer = Harness(this, shared)
        val draft = f.member(MealKitchenFeature.POST_DRAFTS); val publication = f.member(MealKitchenFeature.POST_PUBLICATIONS)
        val other = peer.member(MealKitchenFeature.POST_DRAFTS)
        try {
            f.use(draft) {}
            f.journal.release(draft.participant)
            failure(FailureReason.CONFLICT) { peer.use(other) {} }
            f.use(publication) {} // No call to any draft controller readiness.
            f.journal.release(publication.participant)
            peer.use(other) {}
            f.noIo(); peer.noIo(); assertTrue(shared.boundary.isCurrent(shared.lease))
        } finally { f.close(); peer.close() }
    }

    @Test fun finalDetachKeepsClaimUntilAdmittedUseDrainsAndCannotAuthorizeFurtherWork() = runTest {
        val shared = Shared(); val f = Harness(this, shared); val peer = Harness(this, shared)
        val member = f.member(MealKitchenFeature.POST_DRAFTS); val other = peer.member(MealKitchenFeature.POST_DRAFTS)
        try {
            f.use(member) { use ->
                f.journal.release(member.participant)
                failure(FailureReason.STALE_SESSION) { f.journal.readLegacy(use) }
                failure(FailureReason.CONFLICT) { peer.use(other) {} }
            }
            peer.use(other) {}; f.noIo()
        } finally { f.close(); peer.close() }
    }

    @Test fun sameLeaseOriginWithAnotherStoreFacadeCannotBypassTheLegacyClaim() = runTest {
        val shared = Shared(); val f = Harness(this, shared)
        val wrapper = object : PrivateStateStore {
            override suspend fun read(scope: StorageScope, key: RecordKey) = shared.store.read(scope, key)
            override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>) = shared.store.commit(scope, mutations)
            override suspend fun eraseScope(scope: StorageScope) = shared.store.eraseScope(scope)
        }
        val peer = Harness(this, shared, actualStore = wrapper)
        val member = f.member(MealKitchenFeature.POST_DRAFTS); val other = peer.member(MealKitchenFeature.POST_DRAFTS)
        try {
            f.use(member) {}; failure(FailureReason.CONFLICT) { peer.use(other) {} }
            f.journal.release(member.participant); peer.use(other) {}; f.noIo()
        } finally { f.close(); peer.close() }
    }

    @Test fun exactOwnerCompositionAndPolicyCannotBeSubstitutedInLegacyControllerConstruction() = runTest {
        val f = Harness(this); val other = Harness(this)
        try {
            failure(FailureReason.CONFLICT) { PostDraftController(other.composition, other.shared.ids, f.policy, f.journal) }
            failure(FailureReason.CONFLICT) { PostDraftController(f.composition, f.shared.ids, PostDraftControllerTest.policy(), f.journal) }
            f.noIo(); other.noIo()
        } finally { f.close(); other.close() }
    }

    @Test fun staleOrClosedCompositionStillConstructsAnUnavailableLegacyControllerWithoutIo() = runTest {
        for (clearBoundary in listOf(false, true)) {
            val f = Harness(this)
            try {
                if (clearBoundary) f.shared.boundary.clear()
                else f.composition.release(f.bind(MealKitchenFeature.COOKING))
                val controller = f.controller()
                assertEquals(FailureReason.STALE_SESSION, assertIs<PortResult.Failure>(controller.restoreLocal()).reason)
                f.noIo()
            } finally { f.close() }
        }
    }

    @Test fun outstandingUseCannotBorrowANewOperationEvenBeforeItsLifetimeIsDrained() = runTest {
        val f = Harness(this); val member = f.member(MealKitchenFeature.POST_DRAFTS)
        var old: DraftJournalUse? = null
        try {
            old = f.composition.operate(member.borrower) {
                f.journal.enter(f.composition.composerPermit(member.borrower), member.participant)
            }
            failure(FailureReason.STALE_SESSION) { f.journal.readLegacy(checkNotNull(old)) }
            f.use(member) { fresh ->
                failure(FailureReason.STALE_SESSION) { f.journal.readLegacy(checkNotNull(old)) }
                f.journal.preflightLegacy(fresh, PostRecord(NOW))
            }
            f.noIo()
        } finally { old?.let { f.journal.leave(it) }; f.close() }
    }

    @Test fun preparedLegacyMutationRequiresExactObjectAndCurrentUseAndCannotWriteAnotherKey() = runTest {
        val f = Harness(this); val member = f.member(MealKitchenFeature.POST_DRAFTS)
        lateinit var prior: StoreMutation.Put
        try {
            f.use(member) { use ->
                val entry = f.journal.readLegacy(use); prior = f.journal.mutationLegacy(use, entry, record())
                val copy = StoreMutation.Put(prior.key, prior.expectedRevision, prior.schemaVersion, prior.payload)
                failure(FailureReason.CONFLICT) { f.journal.commitLegacy(use, copy) }
                failure(FailureReason.CONFLICT) { f.journal.commitLegacy(use, StoreMutation.Put(RecordKey("other", ORIGIN), null, 1, prior.payload)) }
                assertEquals(0, f.shared.store.writes)
                f.journal.commitLegacy(use, prior)
                assertEquals(1, f.shared.store.writes)
            }
            f.use(member) { use -> failure(FailureReason.CONFLICT) { f.journal.commitLegacy(use, prior) } }
            assertEquals(setOf(KEY), f.shared.store.records.keys); assertTrue(f.shared.calls.isEmpty())
        } finally { f.close() }
    }

    @Test fun readRejectsUnsupportedSchemaAndClockRollbackWithoutChangingTheOriginalBytes() = runTest {
        val f = Harness(this); val member = f.member(MealKitchenFeature.POST_DRAFTS)
        try {
            val encoded = PostDraftCodec(ORIGIN, f.policy).encode(record())
            f.shared.store.records[KEY] = PrivateRecord(1, 2, encoded)
            f.use(member) { use -> failure(FailureReason.INVALID_DATA) { f.journal.readLegacy(use) } }
            f.shared.store.records[KEY] = PrivateRecord(1, 1, encoded); f.shared.now = NOW - 1
            f.use(member) { use -> failure(FailureReason.CONFLICT) { f.journal.readLegacy(use) } }
            assertEquals(0, f.shared.store.writes)
            assertContentEquals(encoded.copyForCodec(), f.shared.store.records.getValue(KEY).payload.copyForCodec())
        } finally { f.close() }
    }

    @Test fun actualLegacyControllerUsesOnlySchemaOneNamespaceAndPreservesTextAcrossReplacement() = runTest {
        val shared = Shared(); val f = Harness(this, shared); val replacement = Harness(this, shared)
        try {
            val controller = f.controller()
            val created = value(controller.newLocalDraft("First text", "Description")).selected!!
            val edited = value(controller.editCaption(created.clientDraftId, "Retained text")).selected!!
            val before = shared.store.records.getValue(KEY)
            assertEquals(1, before.schemaVersion); assertTrue(edited.localAcknowledged)
            controller.close()
            val restored = value(replacement.controller().restoreLocal())
            val local = restored.localDrafts.single()
            assertEquals(created.clientDraftId, local.clientDraftId); assertEquals(edited.localRevision, local.localRevision)
            assertEquals("Retained text", local.caption); assertEquals("Description", local.altText)
            assertContentEquals(before.payload.copyForCodec(), shared.store.records.getValue(KEY).payload.copyForCodec())
            assertEquals(setOf(KEY), shared.store.records.keys); assertTrue(shared.calls.isEmpty()); assertEquals(0, shared.store.erases)
        } finally { f.close(); replacement.close() }
    }

    @Test fun actualOriginalIntentSurvivesUnknownReplyAndControllerReplacementWithoutNewIdsOrWrites() = runTest {
        val shared = Shared(); val f = Harness(this, shared); val replacement = Harness(this, shared)
        try {
            val controller = f.controller()
            value(controller.newLocalDraft("Exact original", ""))
            value(controller.saveExplicitly())
            val original = PostDraftCodec(ORIGIN, f.policy).decode(shared.store.records.getValue(KEY).payload).command!!
            val bytes = original.call().body!!.copyForCodec(); val readsBeforeClose = shared.store.reads
            controller.close()
            val writes = shared.store.writes; val ids = shared.idCalls
            val restored = value(replacement.controller().restoreLocal())
            assertNotNull(restored.pending); assertFalse(restored.serverAcknowledged)
            val retained = PostDraftCodec(ORIGIN, f.policy).decode(shared.store.records.getValue(KEY).payload).command!!
            assertEquals(original.id, retained.id); assertEquals(original.created, retained.created)
            assertEquals(original.etag, retained.etag); assertEquals(original.operation, retained.operation)
            assertContentEquals(bytes, retained.call().body!!.copyForCodec())
            assertEquals(writes, shared.store.writes); assertEquals(ids, shared.idCalls)
            assertTrue(shared.store.reads > readsBeforeClose); assertEquals(1, shared.calls.size)
        } finally { f.close(); replacement.close() }
    }

    @Test fun closingActualDraftControllerDoesNotReleaseRegisteredPublicationParticipantClaim() = runTest {
        val shared = Shared(); val f = Harness(this, shared); val peer = Harness(this, shared)
        try {
            val controller = f.controller()
            val publication = f.member(MealKitchenFeature.POST_PUBLICATIONS)
            value(controller.newLocalDraft("Private draft")); controller.close()
            val replacement = peer.controller()
            assertEquals(FailureReason.CONFLICT, assertIs<PortResult.Failure>(replacement.restoreLocal()).reason)
            f.use(publication) {}
            f.journal.release(publication.participant)
            assertEquals("Private draft", value(replacement.restoreLocal()).localDrafts.single().caption)
            assertTrue(shared.boundary.isCurrent(shared.lease)); assertTrue(shared.calls.isEmpty())
        } finally { f.close(); peer.close() }
    }

    @Test fun closedControllerWithSuspendedActualCommitRetainsClaimUntilFinallyDrains() = runTest {
        val shared = Shared(); val f = Harness(this, shared); val peer = Harness(this, shared)
        val controller = f.controller(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        shared.store.afterCommit = { changes -> if (changes.any { it.key == KEY }) {
            entered.complete(Unit); withContext(NonCancellable) { release.await() }
        } }
        val pending = async { controller.newLocalDraft("Unknown local write") }
        try {
            entered.await(); controller.close()
            val replacement = peer.controller()
            assertEquals(FailureReason.CONFLICT, assertIs<PortResult.Failure>(replacement.restoreLocal()).reason)
            shared.store.afterCommit = {}; release.complete(Unit)
            assertEquals(FailureReason.STALE_SESSION, assertIs<PortResult.Failure>(pending.await()).reason)
            val restored = value(replacement.restoreLocal())
            assertEquals("Unknown local write", restored.localDrafts.single().caption)
            assertFalse(restored.localDrafts.single().localAcknowledged)
            assertFalse(restored.serverAcknowledged); assertEquals(1, shared.store.writes); assertTrue(shared.calls.isEmpty())
        } finally {
            shared.store.afterCommit = {}; release.complete(Unit)
            withContext(NonCancellable) { pending.cancelAndJoin(); f.close(); peer.close() }
        }
    }

    @Test fun cancelledEnterCannotClaimAndDetachedUseCannotAcquireAfterBoundaryInvalidation() = runTest {
        val f = Harness(this); val member = f.member(MealKitchenFeature.POST_DRAFTS)
        try {
            f.composition.operate(member.borrower) {
                val permit = f.composition.composerPermit(member.borrower)
                coroutineScope {
                    val child = async(start = CoroutineStart.LAZY) { f.journal.enter(permit, member.participant) }
                    child.cancelAndJoin()
                }
                val use = f.journal.enter(permit, member.participant)
                f.shared.boundary.clear()
                try { failure(FailureReason.STALE_SESSION) { f.journal.readLegacy(use) } }
                finally { f.journal.leave(use) }
            }
            fail("An invalidated composition must not return normally")
        } catch (failure: MealFailure) {
            assertEquals(FailureReason.STALE_SESSION, failure.reason); f.noIo()
        } finally { f.close() }
    }

    private class Shared(actor: ActorKind = ActorKind.ACCOUNT) {
        val scope = StorageScope("synthetic-journal-owner", actor, "private-owner")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope)
        val store = PostDraftControllerTest.Store(scope)
        var now = NOW; var idCalls = 0
        val ids = MealOperationIds { PostDraftControllerTest.number(++idCalls + 500) }
        val calls = mutableListOf<ApiCall>()
        val transport = object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                assertSame(this@Shared.lease, lease); calls += call
                return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN)
            }
        }
    }
    private class Member(val borrower: MealKitchenComposition.Borrower, val participant: DraftJournalParticipant)
    private class Harness(test: TestScope, val shared: Shared = Shared(), actualStore: PrivateStateStore = shared.store) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val policy = PostDraftControllerTest.policy()
        val composition = MealKitchenComposition(
            AuthenticatedMealPlanningAccess(shared.lease, ORIGIN, actualStore, shared.transport, true),
            shared.boundary, dispatcher, EpochClock { shared.now }, ConnectivityPort { Connectivity.ONLINE })
        val journal = PostDraftJournalOwner(composition, policy)
        private val borrowers = mutableListOf<MealKitchenComposition.Borrower>()
        private val controllers = mutableListOf<PostDraftController>()
        fun bind(feature: MealKitchenFeature, check: suspend () -> Unit = {}) =
            composition.bind(feature, object : MealKitchenHooks { override suspend fun checkCurrent() = check() }).also { borrowers += it }
        fun member(feature: MealKitchenFeature, check: suspend () -> Unit = {}): Member {
            val borrower = bind(feature, check)
            return Member(borrower, journal.register(borrower))
        }
        fun controller() = PostDraftController(composition, shared.ids, policy, journal).also { controllers += it }
        suspend fun <T> use(member: Member, action: suspend (DraftJournalUse) -> T): T = composition.operate(member.borrower) {
            val use = journal.enter(composition.composerPermit(member.borrower), member.participant)
            try { action(use) } finally { journal.leave(use) }
        }
        fun noIo() {
            assertEquals(0, shared.store.reads); assertEquals(0, shared.store.writes); assertEquals(0, shared.store.erases)
            assertTrue(shared.calls.isEmpty())
        }
        suspend fun close() {
            controllers.forEach { it.close() }
            shared.boundary.clear()
            borrowers.forEach { composition.release(it) }
        }
    }
    private companion object {
        const val ORIGIN = "00000000-0000-4000-8000-000000000081"
        const val CLIENT = "00000000-0000-4000-8000-000000000082"
        const val NOW = 1_800_000_000_000L
        val KEY = RecordKey("mealflow.post-drafts.v1", ORIGIN)
        fun record() = PostRecord(NOW, listOf(PostLocal(CLIENT, 1, "Retained", null)), listOf(CLIENT))
        suspend fun failure(reason: FailureReason, action: suspend () -> Unit) =
            assertEquals(reason, assertFailsWith<MealFailure> { action() }.reason)
        fun <T> value(result: PortResult<T>) = assertIs<PortResult.Value<T>>(result).value
    }
}
