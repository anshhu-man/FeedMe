package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.document
import com.feedme.mealflow.social.PostDraftControllerTest.Companion.number
import com.feedme.storage.PostDraftHttpSessionFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Real encrypted SQLite, leased PrivateSessionRuntime access, sole owner and public controller.
 * Credential verification/JCA vault/transport and seeded composer/publication data are synthetic.
 * Controlled database reopen is not process death, native vault or live publication acceptance.
 * No production factory or record migration is enabled by this test-only composition. */
@OptIn(ExperimentalCoroutinesApi::class)
class PostDraftCurrentStorageTest {
    @Test fun currentLocalCreationAndEditUseActualEncryptedCasAfterTombstone() = runTest { fixture { f ->
        val prior = value(f.session.access.store.commit(SCOPE, listOf(StoreMutation.Put(f.key, null, 2, bytes("synthetic removed row"))))).getValue(f.key)!!
        value(f.session.access.store.commit(SCOPE, listOf(StoreMutation.Delete(f.key, prior))))
        val created = f.controller.newLocalDraft(CAPTION, ALT).value().selected!!
        assertTrue(created.localAcknowledged); assertEquals(2, f.record().schemaVersion)
        assertEquals(prior + 2, f.record().revision)
        val revision = f.record().revision
        val edited = f.controller.editCaption(created.clientDraftId, "Changed current text").value()
        assertEquals(revision + 1, f.record().revision); assertTrue(edited.selected!!.localAcknowledged)
        assertEquals(ALT, f.current().locals.single().content.altText)
        assertFalse(edited.serverAcknowledged); assertEquals(1, f.ids); assertTrue(f.calls.isEmpty())
    } }

    @Test fun fullComposerChoicesSurviveActualEditsAndControlledRuntimeReopen() = runTest { fixture { f ->
        val original = f.composer(); f.seed(listOf(original)); f.open(CLIENT)
        val edited = f.controller.editCaption(CLIENT, "Only caption changed").value()
        assertTrue(edited.selected!!.localAcknowledged); assertTrue(edited.selected!!.requiresReviewedSave)
        assertFalse(edited.serverAcknowledged); f.assertChoices(original, f.current().locals.single())
        f.controller.editAltText(CLIENT, "Only description changed").value()
        val stored = f.record(); val snapshot = f.current().locals.single()
        f.assertChoices(original, snapshot)
        val oldLease = f.session.access.lease; val oldOrigin = f.session.access.originBinding
        f.reopenRuntime()
        assertNotSame(oldLease, f.session.access.lease); assertEquals(oldOrigin, f.session.access.originBinding)
        assertTrue(postSame(stored, f.record()))
        val restored = f.controller.restoreLocal().value(); assertFalse(restored.serverAcknowledged)
        val open = f.controller.openLocal(CLIENT).value()
        assertEquals("Only caption changed", open.selected!!.caption)
        assertEquals("Only description changed", open.selected!!.altText)
        assertContentEquals(snapshot.exactUtf8.copyForCodec(), f.current().locals.single().exactUtf8.copyForCodec())
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.ids)
    } }

    @Test fun actualBeforeAndAfterCommitFailuresRetainExactUnacknowledgedEditOnSameLease() = runTest {
        for (after in listOf(false, true)) fixture { f ->
            f.session.failNextDataCommit(after)
            failure(f.controller.newLocalDraft(CAPTION, ALT), if (after) FailureReason.OUTCOME_UNKNOWN else FailureReason.STORAGE_FAILURE)
            val shown = f.controller.states.value.selected!!
            assertFalse(shown.localAcknowledged); assertFalse(f.controller.states.value.serverAcknowledged)
            val held = assertNotNull(PostDraftCurrentHeld.edit(f.access, f.session.boundary))
            val exact = held.proposed.exactUtf8.copyForCodec()
            assertEquals(after, value(f.session.access.store.read(SCOPE, f.key)) != null)
            f.replaceControllers()
            assertSame(held, PostDraftCurrentHeld.edit(f.access, f.session.boundary))
            f.open(shown.clientDraftId)
            assertFalse(f.controller.states.value.selected!!.localAcknowledged)
            assertContentEquals(exact, assertNotNull(PostDraftCurrentHeld.edit(f.access, f.session.boundary)).proposed.exactUtf8.copyForCodec())
            // The explicit text edit is a new local revision, not replay of a server command.
            val expected = f.snapshots.withText(held.proposed, CAPTION, ALT, held.proposed.localRevision + 1)
            val retry = f.controller.editCaption(shown.clientDraftId, CAPTION).value()
            assertTrue(retry.selected!!.localAcknowledged); assertFalse(retry.serverAcknowledged)
            assertContentEquals(expected.exactUtf8.copyForCodec(), f.current().locals.single().exactUtf8.copyForCodec())
            assertContentEquals(exact, held.proposed.exactUtf8.copyForCodec())
            assertEquals(1, f.ids); assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun fullComposerLostCommitReplyReopensWithoutReconstructingLiveProofOrLeakingPlaintext() = runTest { fixture { f ->
        val original = f.composer(); f.seed(listOf(original)); f.open(CLIENT)
        f.session.failNextDataCommit(afterCommit = true)
        failure(f.controller.editCaption(CLIENT, CAPTION), FailureReason.OUTCOME_UNKNOWN)
        val held = assertNotNull(PostDraftCurrentHeld.edit(f.access, f.session.boundary))
        val exact = held.proposed.exactUtf8.copyForCodec(); f.assertChoices(original, held.proposed)
        assertFalse(f.controller.states.value.selected!!.localAcknowledged)
        val oldAccess = f.access; val oldBoundary = f.session.boundary
        val stored = f.record(); f.reopenRuntime()
        assertNull(PostDraftCurrentHeld.edit(oldAccess, oldBoundary))
        assertNull(PostDraftCurrentHeld.edit(f.access, f.session.boundary)); assertTrue(postSame(stored, f.record()))
        f.open(CLIENT); assertFalse(f.controller.states.value.selected!!.localAcknowledged)
        assertFalse(f.controller.states.value.serverAcknowledged)
        assertContentEquals(exact, f.current().locals.single().exactUtf8.copyForCodec())
        val accepted = f.controller.editCaption(CLIENT, CAPTION).value()
        assertTrue(accepted.selected!!.localAcknowledged); assertFalse(accepted.serverAcknowledged)
        f.assertChoices(original, f.current().locals.single())
        val origin = f.session.access.originBinding
        f.closeControllers(); f.session.closeRuntimeAndStores()
        f.session.assertNoPlaintext(listOf(CAPTION, ALT, CLIENT, origin, "private-source-disclosure", "mealflow.post-drafts.v1", TOKEN))
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.ids)
    } }

    @Test fun configuredOwnerDoesNotMigrateExistingLegacySqliteRowOnReadOrEdit() = runTest { fixture(configured = false) { f ->
        val created = f.controller.newLocalDraft(CAPTION, ALT).value().selected!!
        val legacy = f.record(); assertEquals(1, legacy.schemaVersion)
        f.replaceControllers(configured = true)
        val writes = f.session.localWriteStatements
        f.open(created.clientDraftId); assertTrue(postSame(legacy, f.record()))
        assertEquals(writes, f.session.localWriteStatements)
        f.controller.editCaption(created.clientDraftId, "Still legacy").value()
        assertEquals(1, f.record().schemaVersion); assertEquals(1, f.ids); assertTrue(f.calls.isEmpty())
    } }

    @Test fun unconfiguredOwnerCannotReadOrRewriteActualCurrentSqliteRow() = runTest { fixture { f ->
        f.seed(listOf(f.composer())); val original = f.record()
        f.replaceControllers(configured = false)
        val writes = f.session.localWriteStatements
        failure(f.controller.restoreLocal(), FailureReason.NOT_CONFIGURED)
        assertTrue(f.controller.states.value.localDrafts.isEmpty()); assertFalse(f.controller.states.value.serverAcknowledged)
        assertEquals(writes, f.session.localWriteStatements); assertTrue(postSame(original, f.record()))
        assertEquals(0, f.ids); assertTrue(f.calls.isEmpty())
    } }

    @Test fun actualSqliteTerminalClosesOnlyPublishedRootAndPreservesNewerUnrelatedComposer() = runTest { fixture { f ->
        val original = f.composer(OTHER)
        f.seed(listOf(f.snapshots.create(CLIENT, 1, DraftLocalContentV1.TextV1("Caption", null), DraftServerAssociationV1.NotObserved), original))
        val link = f.hold(); f.open(OTHER)
        f.controller.editCaption(OTHER, "A newer unrelated meal").value()
        val exact = f.current().locals.single { it.clientDraftId == OTHER }
        f.assertChoices(original, exact)
        f.terminal(link)
        val state = f.controller.states.value
        assertEquals(OTHER, state.selected!!.clientDraftId); assertFalse(state.serverAcknowledged)
        assertTrue(state.localDrafts.none { it.clientDraftId == CLIENT })
        assertContentEquals(exact.exactUtf8.copyForCodec(), f.current().locals.single().exactUtf8.copyForCodec())
        assertEquals(POST, assertIs<PostDraftTerminalV2.Published>(f.current().terminals.single()).postId)
        f.reopenRuntime(); f.controller.restoreLocal().value()
        failure(f.controller.openLocal(CLIENT), FailureReason.NOT_FOUND)
        assertFalse(f.controller.states.value.serverAcknowledged)
        assertContentEquals(exact.exactUtf8.copyForCodec(), f.current().locals.single().exactUtf8.copyForCodec())
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.ids)
    } }

    private suspend fun TestScope.fixture(configured: Boolean = true, action: suspend (Fixture) -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = PostDraftHttpSessionFixture.open(dispatcher,
            StoredCredentials.Account(SCOPE, SecretText(TOKEN), null, Long.MAX_VALUE, SecretText(DEVICE)))
        var fixture: Fixture? = null
        try { fixture = Fixture(session, dispatcher, configured); action(fixture); session.requireNoNativeWork() }
        finally { try { fixture?.closeControllers() } finally { session.close() } }
    }

    private class Fixture(val session: PostDraftHttpSessionFixture, private val dispatcher: CoroutineDispatcher,
        private var configured: Boolean) {
        val policy = PostDraftClientPolicy(8, 1_048_576, 262_144, 64, 64, 2, 100, 60_000)
        val publicationPolicy = PostPublicationClientPolicy(1_048_576, 65_536, 8192, 8, 32, 8, 8, 8, 8, 65_536, 4096, 60_000)
        val snapshots = DraftLocalSnapshotCodecV1(policy, publicationPolicy)
        private val links = PublicationOriginalLinkCodecV1(publicationPolicy, snapshots)
        val calls = mutableListOf<ApiCall>(); var ids = 0
        private val transport = object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                calls += call; return PortResult.Failure(FailureReason.NOT_CONFIGURED)
            }
        }
        var access = AuthenticatedMealPlanningAccess.fromSession(session.access, transport); private set
        private lateinit var composition: MealKitchenComposition
        private lateinit var owner: PostDraftJournalOwner
        lateinit var controller: PostDraftController; private set
        private lateinit var publicationBorrower: MealKitchenComposition.Borrower
        private lateinit var publicationParticipant: DraftJournalParticipant
        private var use: DraftJournalUse? = null
        private var contribution: DraftMutationContribution? = null
        private var open = false
        val key get() = RecordKey("mealflow.post-drafts.v1", session.access.originBinding)
        private val binding get() = PublicationJournalBindingData(SCOPE.environment, session.access.originBinding, session.access.originBinding, ACCOUNT)
        val codec get() = PostDraftV2Codec(SCOPE.environment, session.access.originBinding, policy, publicationPolicy, snapshots, links)
        init { createControllers() }
        private fun createControllers() {
            access = AuthenticatedMealPlanningAccess.fromSession(session.access, transport)
            composition = MealKitchenComposition(access, session.boundary, dispatcher, EpochClock { NOW }, ConnectivityPort { Connectivity.ONLINE })
            owner = PostDraftJournalOwner(composition, policy, if (configured) publicationPolicy else null)
            controller = PostDraftController(composition, MealOperationIds { number(200 + ++ids) }, policy, owner)
            publicationBorrower = composition.bind(MealKitchenFeature.POST_PUBLICATIONS, object : MealKitchenHooks {
                override suspend fun checkCurrent() = Unit
                override fun beforeCommit(mutations: List<StoreMutation>) {
                    owner.beforePublicationCommit(checkNotNull(use), checkNotNull(contribution), mutations)
                }
            })
            publicationParticipant = owner.register(publicationBorrower); open = true
        }
        suspend fun closeControllers() {
            if (!open) return
            value(controller.close()); owner.release(publicationParticipant); composition.release(publicationBorrower); open = false
        }
        suspend fun replaceControllers(configured: Boolean = this.configured) {
            closeControllers(); this.configured = configured; createControllers()
        }
        suspend fun reopenRuntime() { closeControllers(); session.reopen(); createControllers() }
        suspend fun record() = assertNotNull(value(session.access.store.read(SCOPE, key)))
        suspend fun current() = record().let { codec.decode(it.schemaVersion, it.payload) }
        suspend fun seed(locals: List<DraftLocalSnapshotV1>) {
            // Explicit fixture row through the actual leased encrypted CAS, not a composer UI claim.
            value(session.access.store.commit(SCOPE, listOf(StoreMutation.Put(key, null, 2,
                codec.encode(PostDraftV2Record(NOW, locals, locals.map { it.clientDraftId }))))))
        }
        suspend fun open(client: String) { controller.restoreLocal().value(); controller.openLocal(client).value() }
        fun composer(client: String = CLIENT) = snapshots.create(client, 3, DraftLocalContentV1.ComposerV2(document(buildJsonObject {
            put("caption", "Full private composer"); put("altText", ALT)
            put("mediaIds", JsonArray(listOf(JsonPrimitive(number(41)), JsonPrimitive(number(40)))))
            put("audience", buildJsonObject { put("kind", "circles"); put("circleIds", JsonArray(listOf(JsonPrimitive(number(43)), JsonPrimitive(number(42))))) })
            put("keepOnPlate", true); put("allowRecipeSaves", true); put("saveDisclosureVersion", "private-source-version")
            put("sourcePostId", SOURCE.uppercase()); put("attachment", buildJsonObject {
                put("recipeVersionId", SOURCE.uppercase()); put("reviewStatus", "reviewed"); put("rightsBasis", "catalogRedistributable")
                put("confirmedChanges", JsonArray(listOf(JsonPrimitive("Preserve complete recipe source"))))
            })
        }), "private-source-disclosure"), DraftServerAssociationV1.NotObserved)
        fun assertChoices(before: DraftLocalSnapshotV1, after: DraftLocalSnapshotV1) {
            val a = assertIs<DraftLocalContentV1.ComposerV2>(before.content); val b = assertIs<DraftLocalContentV1.ComposerV2>(after.content)
            assertEquals(a.exactChoices.json().jsonObject - setOf("caption", "altText"), b.exactChoices.json().jsonObject - setOf("caption", "altText"))
            assertEquals(a.historicalDisclosureText, b.historicalDisclosureText)
        }
        private suspend fun <T> publication(action: suspend (DraftJournalUse) -> T): T = composition.operate(publicationBorrower) {
            val admitted = owner.enter(composition.composerPermit(publicationBorrower), publicationParticipant); use = admitted
            try { action(admitted) } finally { contribution = null; use = null; owner.leave(admitted) }
        }
        private suspend fun commit(admitted: DraftJournalUse, proposed: DraftMutationContribution) {
            contribution = proposed
            val acknowledgement = mealValue(composition.store.commit(SCOPE, owner.mutations(admitted, proposed)))
            owner.verifyPublicationReadback(admitted, proposed, acknowledgement)
        }
        suspend fun hold(): PublicationOriginalLinkV1 = publication { admitted ->
            val pin = owner.readForPublication(admitted, CLIENT, 1)
            val disclosure = PublicationDisclosure("synthetic-v1", "Explicit synthetic disclosure")
            val body = document(buildJsonObject { put("clientDraftId", CLIENT); put("caption", "Caption"); put("mediaIds", JsonArray(emptyList()))
                put("audience", postSelfAudience()); put("keepOnPlate", false); put("allowRecipeSaves", false); put("saveDisclosureVersion", disclosure.version) })
            val link = links.create(binding, COMMAND, NOW, ApiCall("publishPost", body = PrivateBytes(body.encodeUtf8()), idempotencyKey = SecretText(COMMAND)),
                owner.reviewSnapshot(admitted, pin), PublicationReviewTargetV1.DirectLocal, disclosure)
            val publicationCodec = PostPublicationJournalCodecV1(binding, publicationPolicy, links)
            val journal = PublicationJournalV1(binding, NOW, listOf(COMMAND), listOf(PublicationHistoryEntryV1.PendingOriginal(link)))
            val proposed = owner.prepareHold(admitted, pin, link, publicationCodec.reservedPublishedBytes(journal))
            PublicationCrossRecordValidator(publicationCodec, links, snapshots, publicationPolicy, policy)
                .requireConsistent(journal, owner.publicationProjection(admitted, proposed))
            commit(admitted, proposed); link
        }
        suspend fun terminal(link: PublicationOriginalLinkV1) = publication { admitted ->
            // Canonical synthetic response, not actual HTTP authority or publication queue receipt.
            val body = document(buildJsonObject {
                put("id", POST); put("version", 1); put("createdAt", TIME); put("updatedAt", TIME)
                put("author", buildJsonObject { put("userId", ACCOUNT); put("displayName", "Synthetic cook"); put("handle", "synthetic"); put("avatarMediaId", number(99)) })
                put("caption", "Caption"); put("mediaIds", JsonArray(emptyList())); put("audience", JsonObject(postSelfAudience() + ("bindings" to JsonArray(emptyList()))))
                put("status", "published"); put("publishedAt", TIME); put("expiresAt", "2026-09-15T12:00:00Z"); put("keepOnPlate", false)
                put("savePolicy", buildJsonObject { put("allowFutureSaves", false); put("policyVersion", 1); put("disclosureVersion", "synthetic-v1") })
                put("aclVersion", 1); put("capabilities", JsonArray(listOf(JsonPrimitive("view"), JsonPrimitive("delete")))); put("reactionCounts", JsonArray(emptyList()))
            })
            val receipt = PostPublicationAdapter(publicationPolicy.maxResponseBytes).receipt(link.originalIntentForComparison().call, ACCOUNT,
                ApiReply(201, PrivateBytes(body.encodeUtf8()), "\"1\"", contentType = "application/json"))
            commit(admitted, owner.prepareTerminal(admitted, link, receipt))
        }
    }
    private companion object {
        const val CLIENT = "00000000-0000-4000-8000-000000000082"
        const val OTHER = "00000000-0000-4000-8000-000000000083"
        const val COMMAND = "00000000-0000-4000-8000-000000000084"
        const val POST = "00000000-0000-4000-8000-000000000085"
        const val ACCOUNT = "00000000-0000-4000-8000-000000000086"
        const val DEVICE = "00000000-0000-4000-8000-000000000087"
        const val SOURCE = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        const val CAPTION = "private-current-draft-marker"
        const val ALT = "private-current-alt-marker"
        const val TOKEN = "synthetic-current-storage-token"
        const val TIME = "2026-09-14T12:00:00Z"
        const val NOW = 1_800_000_000_000L
        val SCOPE = StorageScope("synthetic-current-draft-storage", ActorKind.ACCOUNT, ACCOUNT)
        fun bytes(value: String) = PrivateBytes(value.encodeToByteArray())
        fun <T> value(result: PortResult<T>) = assertIs<PortResult.Value<T>>(result).value
        fun PortResult<PostDraftState>.value() = value(this)
        fun failure(result: PortResult<*>, reason: FailureReason) = assertEquals(reason, assertIs<PortResult.Failure>(result).reason)
    }
}
