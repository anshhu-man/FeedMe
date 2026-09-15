package com.feedme.mealflow.social

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.mealflow.*
import com.feedme.storage.PostDraftHttpSessionFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Actual encrypted SQLite, native-session runtime and public local-draft/publication methods.
 * No seeded draft/publication journals or hand-made terminal mutation. Credential verification,
 * process-local JCA vault, principal mapping, policy and remote idempotency ledger are synthetic.
 * Controlled runtime/database reopen is NOT process death or a production publication test. */
@OptIn(ExperimentalCoroutinesApi::class)
class PostComposerSqliteTest {
    @Test fun actualNewLocalReviewAndPublishUseEncryptedTransactionsAndRestoreOnlyHistoricalReceipt() = runTest { fixture { f ->
        val created = value(f.drafts.newLocalDraft(CAPTION, null)).selected!!
        assertEquals(ROOT, created.clientDraftId); assertTrue(created.localAcknowledged)
        val row = f.record(); val writes = f.session.localWriteStatements
        val review = value(f.posts.prepareReview(f.target(created.localRevision), f.choices())).review!!
        assertEquals(writes, f.session.localWriteStatements); assertTrue(postSame(row, f.record()))
        assertEquals(0, f.publicationIds); assertTrue(f.calls.isEmpty())
        assertEquals(CAPTION, json(review.snapshot.exactProposedPostWrite).getValue("caption").jsonPrimitive.content)
        val published = value(f.posts.confirmPublish(review.token))
        assertTrue(published.acknowledged); assertEquals(POST, json(published.exactCanonicalPost!!).getValue("id").jsonPrimitive.content)
        assertEquals(1, f.publicationIds); assertEquals(1, f.localIds); assertEquals(1, f.remoteCreations)
        assertEquals(COMMAND, f.calls.single().idempotencyKey!!.use { it })
        assertTrue(f.current().locals.isEmpty()); assertEquals(POST, assertIs<PostDraftTerminalV2.Published>(f.current().terminals.single()).postId)
        val oldLease = f.access.lease; val oldOrigin = f.session.access.originBinding
        f.reopen()
        assertNotSame(oldLease, f.access.lease); assertEquals(oldOrigin, f.session.access.originBinding)
        val beforeRestore = f.session.localWriteStatements
        assertFalse(value(f.posts.restore()).acknowledged)
        value(f.drafts.restoreLocal())
        assertEquals(FailureReason.NOT_FOUND, assertIs<PortResult.Failure>(f.drafts.openLocal(ROOT)).reason)
        assertEquals(beforeRestore, f.session.localWriteStatements)
        assertEquals(1, f.calls.size); assertEquals(1, f.remoteCreations); assertEquals(1, f.publicationIds)
        f.closeControllers(); f.session.closeRuntimeAndStores()
        f.session.assertNoPlaintext(listOf(CAPTION, ROOT, COMMAND, TOKEN, oldOrigin, "mealflow.post-publications.v1"))
    } }

    @Test fun lostRemoteReplyReopensActualSqliteAndExplicitlyReplaysOnlyOriginalPreservingNewerText() = runTest { fixture { f ->
        val local = value(f.drafts.newLocalDraft(CAPTION, null)).selected!!
        val review = value(f.posts.prepareReview(f.target(local.localRevision), f.choices())).review!!
        f.loseOneReply = true
        val pending = value(f.posts.confirmPublish(review.token))
        assertFalse(pending.acknowledged); assertEquals(1, pending.pending!!.observedAttempts)
        assertEquals(1, f.remoteCreations); assertEquals(1, f.calls.size)
        value(f.drafts.openLocal(ROOT)); value(f.drafts.editCaption(ROOT, NEWER))
        val newer = f.current().locals.single(); assertEquals(NEWER, newer.content.caption)
        val originalBody = f.calls.single().body!!.copyForCodec()
        f.reopen(); f.time += 120_000
        val before = f.calls.size
        val restored = value(f.posts.restore()); assertFalse(restored.acknowledged)
        assertEquals(COMMAND, restored.pending!!.commandId); assertEquals(before, f.calls.size)
        val retry = value(f.posts.prepareOriginalRetry()).retry!!
        assertEquals(COMMAND, retry.original.commandId)
        assertContentEquals(originalBody, retry.original.exactOriginalPostWrite.encodeUtf8())
        assertEquals(newer.localRevision, retry.separatelyObservedCurrentLocal.localRevision)
        assertEquals(1L, retry.original.originalReviewedLocal.localRevision)
        val outcome = f.posts.retryOriginal(retry.token)
        assertEquals(FailureReason.CONFLICT, assertIs<PortResult.Failure>(outcome).reason)
        run {
            val command = assertNotNull(value(f.session.access.store.read(SCOPE, RecordKey("feedme.command.metadata", COMMAND))))
            val phase = Json.parseToJsonElement(command.payload.copyForCodec().decodeToString()).jsonObject.getValue("phase").jsonPrimitive.content
            assertEquals("RECEIPT_READY", phase)
            assertEquals(2, f.calls.size); assertEquals(1, f.remoteCreations)
            assertNotNull(f.current().localPending); assertNull(PostDraftCurrentHeld.edit(f.access, f.session.boundary))
        }
        // Explicitly retain the exact restored local snapshot in THIS new lease. This is a
        // new local-only ACK, not reconstruction of the pre-restart edit or dummy revision.
        value(f.drafts.restoreLocal()); value(f.drafts.openLocal(ROOT))
        val beforeRetention = f.record(); val localIds = f.localIds; val publicationIds = f.publicationIds
        val retention = value(f.drafts.prepareRestoredLocalRetention(ROOT, newer.localRevision))
        assertEquals(NEWER, retention.snapshot.caption); assertEquals(newer.localRevision, retention.snapshot.localRevision)
        assertTrue(postSame(beforeRetention, f.record()))
        assertTrue(value(f.drafts.confirmRestoredLocalRetention(retention.token)).selected!!.localAcknowledged)
        assertEquals(beforeRetention.revision + 1, f.record().revision)
        assertContentEquals(beforeRetention.payload.copyForCodec(), f.record().payload.copyForCodec())
        assertEquals(localIds, f.localIds); assertEquals(publicationIds, f.publicationIds); assertEquals(2, f.calls.size)
        val freshRetry = value(f.posts.prepareOriginalRetry()).retry!!
        assertEquals(COMMAND, freshRetry.original.commandId)
        assertContentEquals(originalBody, freshRetry.original.exactOriginalPostWrite.encodeUtf8())
        val published = value(f.posts.retryOriginal(freshRetry.token)); assertTrue(published.acknowledged)
        assertEquals(2, f.calls.size); assertEquals(1, f.remoteCreations); assertEquals(1, f.publicationIds)
        assertContentEquals(originalBody, f.calls.last().body!!.copyForCodec())
        assertEquals(COMMAND, f.calls.last().idempotencyKey!!.use { it })
        assertTrue(f.current().locals.isEmpty())
        val remainder = f.current().remainders.single()
        assertEquals(NEWER, remainder.content.caption); assertEquals(newer.localRevision, remainder.newerLocalRevision)
        assertEquals(CAPTION, json(published.exactCanonicalPost!!).getValue("caption").jsonPrimitive.content)
    } }

    @Test fun beforeAndAfterActualSqliteCommitFailureRetainOneOriginalAcrossSameLeaseControllerReplacement() = runTest {
        for (after in listOf(false, true)) fixture { f ->
            val local = value(f.drafts.newLocalDraft(CAPTION, null)).selected!!
            val review = value(f.posts.prepareReview(f.target(local.localRevision), f.choices())).review!!
            f.session.failNextDataCommit(after)
            val failure = assertIs<PortResult.Failure>(f.posts.confirmPublish(review.token))
            assertEquals(if (after) FailureReason.OUTCOME_UNKNOWN else FailureReason.STORAGE_FAILURE, failure.reason)
            assertFalse(f.posts.states.value.acknowledged); assertEquals(1, f.publicationIds); assertTrue(f.calls.isEmpty())
            val originalAccess = f.access
            f.replaceControllers(); assertSame(originalAccess, f.access)
            val restored = value(f.posts.restore())
            assertFalse(restored.acknowledged); assertEquals(COMMAND, restored.pending!!.commandId)
            assertEquals(if (after) 0 else null, restored.pending.observedAttempts)
            val retry = value(f.posts.prepareOriginalRetry()).retry!!
            assertContentEquals(review.snapshot.exactProposedPostWrite.encodeUtf8(), retry.original.exactOriginalPostWrite.encodeUtf8())
            assertEquals(COMMAND, retry.original.commandId); assertEquals(START, retry.original.originalCreatedAtMillis)
            assertTrue(value(f.posts.retryOriginal(retry.token)).acknowledged)
            assertEquals(1, f.publicationIds); assertEquals(1, f.remoteCreations); assertEquals(1, f.calls.size)
            assertContentEquals(review.snapshot.exactProposedPostWrite.encodeUtf8(), f.calls.single().body!!.copyForCodec())
        }
    }

    private suspend fun TestScope.fixture(action: suspend (Fixture) -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val session = PostDraftHttpSessionFixture.open(dispatcher,
            StoredCredentials.Account(SCOPE, SecretText(TOKEN), null, Long.MAX_VALUE, SecretText(DEVICE)))
        var actual: Fixture? = null
        try { actual = Fixture(session, dispatcher); action(actual); session.requireNoNativeWork() }
        finally { try { actual?.closeControllers() } finally { session.close() } }
    }
    private class Fixture(val session: PostDraftHttpSessionFixture, val dispatcher: CoroutineDispatcher) {
        val draftPolicy = PostDraftClientPolicy(8, 1_048_576, 262_144, 64, 64, 2, 100, 60_000)
        val policy = PostPublicationClientPolicy(1_048_576, 65_536, 8192, 8, 32, 8, 8, 8, 8, 65_536, 4096, 60_000)
        val snapshots = DraftLocalSnapshotCodecV1(draftPolicy, policy)
        val links = PublicationOriginalLinkCodecV1(policy, snapshots)
        val calls = mutableListOf<ApiCall>(); var localIds = 0; var publicationIds = 0
        var remoteCreations = 0; var loseOneReply = false; var time = START
        private val remote = mutableMapOf<String, Pair<ByteArray, ApiReply>>()
        private val transport: AccountTransport = object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                assertSame(access.lease, lease); assertEquals("publishPost", call.operationId)
                assertTrue(call.pathParameters.isEmpty()); assertTrue(call.queryParameters.isEmpty()); assertNull(call.ifMatch)
                calls += call
                val key = call.idempotencyKey!!.use { it }; val body = call.body!!.copyForCodec()
                val prior = remote[key]
                val reply = if (prior != null) { assertContentEquals(prior.first, body); prior.second }
                    else reply(call).also { remote[key] = body.copyOf() to it; remoteCreations++ }
                if (loseOneReply) { loseOneReply = false; return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
                return PortResult.Value(reply)
            }
        }
        var access: AuthenticatedMealPlanningAccess = AuthenticatedMealPlanningAccess.fromSession(session.access, transport); private set
        private var principals = Source(access, session.boundary)
        private var delivery = Delivery(principals)
        lateinit var drafts: PostDraftController; private set
        lateinit var posts: PostComposerController; private set
        private var open = false
        val key get() = RecordKey("mealflow.post-drafts.v1", session.access.originBinding)
        val codec get() = PostDraftV2Codec(SCOPE.environment, session.access.originBinding, draftPolicy, policy, snapshots, links)
        init { createControllers() }
        private fun createControllers() {
            val composition = MealKitchenComposition(access, session.boundary, dispatcher, EpochClock { time }, ConnectivityPort { Connectivity.ONLINE })
            val owner = PostDraftJournalOwner(composition, draftPolicy, policy)
            drafts = PostDraftController(composition, MealOperationIds { localIds++; ROOT }, draftPolicy, owner)
            val prerequisites = object : PostPublicationLifecyclePrerequisites {
                override val principals get() = this@Fixture.principals
                override val delivery get() = this@Fixture.delivery
                override suspend fun disclosure(owner: PublicationPrerequisiteContext) = PortResult.Value(DISCLOSURE)
                override suspend fun requireNew(owner: PublicationPrerequisiteContext, check: PublicationNewPrerequisiteCheck): PortResult<Unit> {
                    assertSame(access.lease, owner.lease); assertEquals(ACCOUNT, owner.verifiedAccountUserId)
                    assertNotEquals(SCOPE.actorId, owner.verifiedAccountUserId); return PortResult.Value(Unit)
                }
                override suspend fun requireOriginalReplay(owner: PublicationPrerequisiteContext, check: PublicationReplayPrerequisiteCheck): PortResult<Unit> {
                    assertSame(access.lease, owner.lease); assertEquals(ACCOUNT, owner.verifiedAccountUserId)
                    assertTrue(check.observedAttempts > 0); return PortResult.Value(Unit)
                }
            }
            posts = PostComposerController(composition, owner, draftPolicy, policy, prerequisites, MealOperationIds { publicationIds++; COMMAND })
            open = true
        }
        suspend fun closeControllers() { if (open) { value(posts.close()); value(drafts.close()); open = false } }
        suspend fun replaceControllers() { closeControllers(); createControllers() }
        suspend fun reopen() {
            closeControllers(); delivery.revoke(); session.reopen()
            access = AuthenticatedMealPlanningAccess.fromSession(session.access, transport)
            principals = Source(access, session.boundary); delivery = Delivery(principals); createControllers()
        }
        suspend fun record() = assertNotNull(value(session.access.store.read(SCOPE, key)))
        suspend fun current() = record().let { codec.decode(it.schemaVersion, it.payload) }
        fun target(revision: Long) = PublicationTarget.DirectLocal(ROOT, revision)
        fun choices() = ReviewedPostChoices(CAPTION, OptionalValue.Absent, emptyList(), PublicationAudience.OnlyYou,
            false, OptionalValue.Absent, false, DISCLOSURE, OptionalValue.Absent)
    }
    private class Source(val access: AuthenticatedMealPlanningAccess, val boundary: SessionBoundary) : PostPublicationPrincipalIntegration() {
        val owner = Any(); val generation = Any()
        override suspend fun resolve(binding: PublicationSessionBinding) = PortResult.Value(mappedPrincipal(binding, ACCOUNT, owner, generation))
        override suspend fun requireCurrent(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot): PortResult<Unit> =
            if (isCurrent(binding, principal)) PortResult.Value(Unit) else PortResult.Failure(FailureReason.STALE_SESSION)
        override fun isCurrent(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot) =
            matchesSession(binding, access, boundary) && matchesCurrentPrincipal(binding, principal, owner, generation)
    }
    private class Delivery(source: Source) : PostPublicationDeliveryIntegration(source, maxPendingDeliveries = 2) {
        fun revoke() = revokeCurrent()
        override fun capture(binding: PublicationSessionBinding, principal: PublicationPrincipalSnapshot) =
            PortResult.Value(witness(binding, principal, generationFor(principal)))
    }
    private companion object {
        const val START = 1_789_387_200_000L
        const val ROOT = "bbbbbbbb-1000-4000-8000-000000000001"
        const val COMMAND = "bbbbbbbb-1000-4000-8000-000000000002"
        const val POST = "bbbbbbbb-1000-4000-8000-000000000003"
        const val ACCOUNT = "bbbbbbbb-1000-4000-8000-000000000004"
        const val AVATAR = "bbbbbbbb-1000-4000-8000-000000000005"
        const val DEVICE = "bbbbbbbb-1000-4000-8000-000000000006"
        const val CAPTION = "Unique private chickpea dinner SQLite regression"
        const val NEWER = "Newer private dinner must not be silently sent"
        const val TOKEN = "synthetic-publication-sqlite-token-only"
        val SCOPE = StorageScope("test", ActorKind.ACCOUNT, "opaque-sqlite-owner")
        val DISCLOSURE = PublicationDisclosure("synthetic-v1", "Synthetic disclosure; no live-policy acceptance.")
        fun json(document: WireDocument) = Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
        fun reply(call: ApiCall): ApiReply {
            val selected = Json.parseToJsonElement(call.body!!.copyForCodec().decodeToString()).jsonObject
            val result = buildJsonObject {
                put("id", POST); put("version", 1); put("createdAt", "2026-09-14T12:00:00Z"); put("updatedAt", "2026-09-14T12:00:00Z")
                put("author", buildJsonObject { put("userId", ACCOUNT); put("displayName", "Synthetic Cook"); put("handle", "synthetic"); put("avatarMediaId", AVATAR) })
                put("caption", selected.getValue("caption")); put("mediaIds", JsonArray(emptyList())); put("keepOnPlate", false)
                put("audience", buildJsonObject { put("kind", "self"); put("circleIds", JsonArray(emptyList())); put("bindings", JsonArray(emptyList())) })
                put("status", "published"); put("publishedAt", "2026-09-14T12:00:00Z"); put("expiresAt", "2026-09-15T12:00:00Z")
                put("savePolicy", buildJsonObject { put("allowFutureSaves", false); put("policyVersion", 1); put("disclosureVersion", DISCLOSURE.version) })
                put("aclVersion", 1); put("capabilities", JsonArray(listOf(JsonPrimitive("view"), JsonPrimitive("delete")))); put("reactionCounts", JsonArray(emptyList()))
            }
            return ApiReply(201, PrivateBytes(result.toString().encodeToByteArray()), contentType = "application/json", etag = "\"1\"")
        }
        fun <T> value(result: PortResult<T>): T = when (result) {
            is PortResult.Value -> result.value
            is PortResult.Failure -> fail("Expected acknowledged value, got ${result.reason}")
        }
    }
}
