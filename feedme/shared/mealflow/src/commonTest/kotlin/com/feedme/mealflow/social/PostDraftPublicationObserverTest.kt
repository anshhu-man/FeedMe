package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.document
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.number
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Actual shared owner + registered public controller observer over a synthetic CAS store.
 * Canonical synthetic Post data is not a publication queue receipt or caller-delivery proof. */
@OptIn(ExperimentalCoroutinesApi::class)
class PostDraftPublicationObserverTest {
    @Test fun abortedActualTerminalPreparationHidesSelectedRootUntilAnExplicitFreshRead() = runTest {
        val f = Fixture(this)
        try {
            f.seed(); val link = f.hold(); f.open(CLIENT)
            val before = f.store.records.getValue(KEY); val writes = f.store.writes
            f.terminal(link, commit = false)
            val hidden = f.controller.states.value
            assertEquals(PostDraftScreen.LOCAL_LIST, hidden.screen); assertNull(hidden.selected)
            assertTrue(hidden.localDrafts.none { it.clientDraftId == CLIENT })
            assertFalse(hidden.serverAcknowledged); assertEquals(PostDraftIssue.RECONCILIATION_REQUIRED, hidden.issue)
            assertTrue(postSame(before, f.store.records[KEY])); assertEquals(writes, f.store.writes)
            val reads = f.store.reads
            val back = f.controller.back().value()
            assertEquals(reads, f.store.reads); assertTrue(back.localDrafts.none { it.clientDraftId == CLIENT })
            assertFalse(back.serverAcknowledged)
            val refreshed = f.controller.restoreLocal().value()
            assertTrue(refreshed.localDrafts.any { it.clientDraftId == CLIENT }); assertNull(refreshed.selected)
            assertFalse(refreshed.serverAcknowledged); assertEquals(COMMAND, refreshed.publicationHold!!.commandId)
            assertEquals(CLIENT, f.controller.openLocal(CLIENT).value().selected!!.clientDraftId)
            assertTrue(f.calls.isEmpty()); assertEquals(0, f.ids)
        } finally { f.close() }
    }

    @Test fun actualTerminalCommitCannotResurrectClosedRootThroughCachedOrFreshControllerPaths() = runTest {
        val f = Fixture(this)
        try {
            f.seed(); val link = f.hold(); f.open(CLIENT)
            f.terminal(link, commit = true)
            assertTrue(f.controller.states.value.localDrafts.none { it.clientDraftId == CLIENT })
            assertFalse(f.controller.states.value.serverAcknowledged)
            assertTrue(f.current().locals.none { it.clientDraftId == CLIENT })
            assertEquals(POST, assertIs<PostDraftTerminalV2.Published>(f.current().terminals.single()).postId)
            val restored = f.controller.restoreLocal().value()
            assertNull(restored.selected); assertTrue(restored.localDrafts.none { it.clientDraftId == CLIENT })
            assertFalse(restored.serverAcknowledged); assertNull(restored.publicationHold)
            failure(f.controller.openLocal(CLIENT), FailureReason.NOT_FOUND)
            val writes = f.store.writes
            failure(f.controller.editCaption(CLIENT, "Do not resurrect"), FailureReason.CONFLICT)
            assertEquals(writes, f.store.writes); assertTrue(f.current().locals.none { it.clientDraftId == CLIENT })
            assertTrue(f.calls.isEmpty()); assertEquals(0, f.ids)
        } finally { f.close() }
    }

    @Test fun terminalObserverPreservesUnrelatedSelectionAndExactFullHeldEditButFencesItsLateAck() = runTest {
        val f = Fixture(this); val caller = HoldingCaller(StandardTestDispatcher(testScheduler))
        var task: Deferred<PortResult<PostDraftState>>? = null
        try {
            f.seed(withOther = true); val link = f.hold(); f.open(OTHER)
            val before = f.current().locals.single { it.clientDraftId == OTHER }
            task = async(caller) { caller.hold = true; f.controller.editCaption(OTHER, "Actual newer unrelated edit") }
            runCurrent()
            // Stage return, actual operation return, then pause the armed caller-delivery tail.
            repeat(2) { assertEquals(1, caller.pending); caller.releaseOne(); runCurrent() }
            assertEquals(1, caller.pending)
            val held = PostDraftCurrentHeld.edit(f.access, f.boundary)!!
            val exact = held.proposed.exactUtf8.copyForCodec()
            val actual = f.current().locals.single { it.clientDraftId == OTHER }
            assertContentEquals(exact, actual.exactUtf8.copyForCodec())
            assertFalse(held.delivery?.delivered(held) == true)
            f.assertChoices(before, held.proposed)
            f.terminal(link, commit = true)
            val shown = f.controller.states.value
            assertEquals(PostDraftScreen.EDITOR, shown.screen); assertEquals(OTHER, shown.selected!!.clientDraftId)
            assertEquals("Actual newer unrelated edit", shown.selected!!.caption)
            assertFalse(shown.selected!!.localAcknowledged); assertFalse(shown.serverAcknowledged)
            assertSame(held, PostDraftCurrentHeld.edit(f.access, f.boundary))
            assertContentEquals(exact, held.proposed.exactUtf8.copyForCodec())
            assertContentEquals(exact, f.current().locals.single().exactUtf8.copyForCodec())
            caller.releaseAll(); failure(task.await(), FailureReason.STALE_SESSION)
            assertFalse(held.delivery?.delivered(held) == true)
            assertSame(held, PostDraftCurrentHeld.edit(f.access, f.boundary))
            val after = f.controller.states.value
            assertEquals(OTHER, after.selected!!.clientDraftId); assertFalse(after.selected!!.localAcknowledged)
            assertTrue(after.localDrafts.none { it.clientDraftId == CLIENT })
            assertTrue(f.calls.isEmpty()); assertEquals(0, f.ids)
        } finally { caller.releaseAll(); task?.cancelAndJoin(); f.close() }
    }

    @Test fun actualObserverFencesArmedOpenResultSoItCannotRestoreTheDeletedEditor() = runTest {
        val f = Fixture(this); val caller = HoldingCaller(StandardTestDispatcher(testScheduler))
        var task: Deferred<PortResult<PostDraftState>>? = null
        try {
            f.seed(); val link = f.hold(); f.controller.restoreLocal().value()
            task = async(caller) { caller.hold = true; f.controller.openLocal(CLIENT) }
            runCurrent(); assertEquals(1, caller.pending)
            caller.releaseOne(); runCurrent(); assertEquals(1, caller.pending)
            assertEquals(CLIENT, f.controller.states.value.selected!!.clientDraftId)
            f.terminal(link, commit = true)
            assertNull(f.controller.states.value.selected)
            caller.releaseAll(); failure(task.await(), FailureReason.STALE_SESSION)
            assertEquals(PostDraftScreen.LOCAL_LIST, f.controller.states.value.screen)
            assertNull(f.controller.states.value.selected); assertTrue(f.controller.states.value.localDrafts.isEmpty())
            assertFalse(f.controller.states.value.serverAcknowledged)
            assertTrue(f.controller.restoreLocal().value().localDrafts.isEmpty())
            assertTrue(f.calls.isEmpty()); assertEquals(0, f.ids)
        } finally { caller.releaseAll(); task?.cancelAndJoin(); f.close() }
    }

    private class HoldingCaller(private val scheduled: CoroutineDispatcher) : CoroutineDispatcher() {
        var hold = false
        private val held = ArrayDeque<Pair<CoroutineContext, Runnable>>()
        val pending get() = held.size
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (hold) held.addLast(context to block) else scheduled.dispatch(context, block)
        }
        fun releaseOne() { val (context, block) = held.removeFirst(); scheduled.dispatch(context, block) }
        fun releaseAll() { hold = false; while (held.isNotEmpty()) releaseOne() }
    }

    private class Fixture(test: TestScope) {
        val policy = PostDraftClientPolicy(8, 1_048_576, 262_144, 64, 64, 2, 100, 60_000)
        val publicationPolicy = PostPublicationClientPolicy(1_048_576, 65_536, 8192, 8, 32, 8, 8, 8, 8, 65_536, 4096, 60_000)
        val snapshots = DraftLocalSnapshotCodecV1(policy, publicationPolicy)
        val links = PublicationOriginalLinkCodecV1(publicationPolicy, snapshots)
        val scope = StorageScope("synthetic-joint-observer", ActorKind.ACCOUNT, "opaque-private-actor")
        val boundary = SessionBoundary(); val lease = boundary.activate(scope)
        val store = PostDraftControllerTest.Store(scope)
        val calls = mutableListOf<ApiCall>(); var ids = 0
        val access = AuthenticatedMealPlanningAccess(lease, ORIGIN, store, object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                calls += call; return PortResult.Failure(FailureReason.NOT_CONFIGURED)
            }
        }, true)
        val composition = MealKitchenComposition(access, boundary, StandardTestDispatcher(test.testScheduler), EpochClock { NOW }, ConnectivityPort { Connectivity.ONLINE })
        val owner = PostDraftJournalOwner(composition, policy, publicationPolicy)
        val controller = PostDraftController(composition, MealOperationIds { ids++; number(100 + ids) }, policy, owner)
        private var activeUse: DraftJournalUse? = null
        private var activeContribution: DraftMutationContribution? = null
        private val borrower = composition.bind(MealKitchenFeature.POST_PUBLICATIONS, object : MealKitchenHooks {
            override suspend fun checkCurrent() = Unit
            override fun beforeCommit(mutations: List<StoreMutation>) {
                owner.beforePublicationCommit(activeUse ?: error("No current synthetic publication use"),
                    activeContribution ?: error("No actual owner contribution"), mutations)
            }
        })
        private val participant = owner.register(borrower)
        val codec = PostDraftV2Codec(scope.environment, ORIGIN, policy, publicationPolicy, snapshots, links)
        private val binding = PublicationJournalBindingData(scope.environment, ORIGIN, ORIGIN, ACCOUNT)
        private val publicationCodec = PostPublicationJournalCodecV1(binding, publicationPolicy, links)
        private val cross = PublicationCrossRecordValidator(publicationCodec, links, snapshots, publicationPolicy, policy)
        private val disclosure = PublicationDisclosure("synthetic-v1", "Actual synthetic disclosure data")
        fun current() = codec.decode(2, store.records.getValue(KEY).payload)
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
        private suspend fun <T> publication(action: suspend (DraftJournalUse) -> T): T = composition.operate(borrower) {
            val use = owner.enter(composition.composerPermit(borrower), participant); activeUse = use
            try { action(use) } finally { activeContribution = null; activeUse = null; owner.leave(use) }
        }
        suspend fun hold(): PublicationOriginalLinkV1 = publication { use ->
            val pin = owner.readForPublication(use, CLIENT, 1); val snapshot = owner.reviewSnapshot(use, pin)
            val body = document(buildJsonObject {
                put("clientDraftId", CLIENT); put("caption", "Caption"); put("mediaIds", JsonArray(emptyList()))
                put("audience", postSelfAudience()); put("keepOnPlate", false); put("allowRecipeSaves", false)
                put("saveDisclosureVersion", disclosure.version)
            })
            val link = links.create(binding, COMMAND, NOW, ApiCall("publishPost", body = PrivateBytes(body.encodeUtf8()), idempotencyKey = SecretText(COMMAND)),
                snapshot, PublicationReviewTargetV1.DirectLocal, disclosure)
            val historical = PublicationJournalV1(binding, NOW, listOf(COMMAND), listOf(PublicationHistoryEntryV1.PendingOriginal(link)))
            val contribution = owner.prepareHold(use, pin, link, publicationCodec.reservedPublishedBytes(historical))
            cross.requireConsistent(historical, owner.publicationProjection(use, contribution))
            commitDraftContribution(use, contribution); link
        }
        suspend fun terminal(link: PublicationOriginalLinkV1, commit: Boolean) = publication { use ->
            val contribution = owner.prepareTerminal(use, link, receipt(link))
            if (commit) commitDraftContribution(use, contribution)
        }
        private suspend fun commitDraftContribution(use: DraftJournalUse, contribution: DraftMutationContribution) {
            activeContribution = contribution
            val result = value(composition.store.commit(scope, owner.mutations(use, contribution)))
            owner.verifyPublicationReadback(use, contribution, result)
        }
        private fun receipt(link: PublicationOriginalLinkV1): PostPublicationReceipt {
            val body = document(buildJsonObject {
                put("id", POST); put("version", 1); put("createdAt", TIME); put("updatedAt", TIME)
                put("author", buildJsonObject { put("userId", ACCOUNT); put("displayName", "Synthetic cook"); put("handle", "synthetic"); put("avatarMediaId", number(99)) })
                put("caption", "Caption"); put("mediaIds", JsonArray(emptyList()))
                put("audience", JsonObject(postSelfAudience() + ("bindings" to JsonArray(emptyList()))))
                put("status", "published"); put("publishedAt", TIME); put("expiresAt", "2026-09-15T12:00:00Z"); put("keepOnPlate", false)
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
        suspend fun close() { controller.close(); owner.release(participant); composition.release(borrower); boundary.clear() }
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
