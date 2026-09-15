package com.feedme.server.http

import com.feedme.core.ports.*
import com.feedme.mealflow.*
import com.feedme.mealflow.social.*
import com.feedme.server.config.LocalServerConfig
import com.feedme.server.db.PostgresTestCluster
import com.feedme.server.social.drafts.PostDraftTestFixture
import com.feedme.storage.PostDraftHttpSessionFixture
import com.feedme.transport.ApiEndpoint
import com.feedme.transport.FeedMeTransport
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.Timeout
import kotlin.test.*

/** Public session/composition/controllers -> canonical account transport -> actual CIO HTTP ->
 * actual PostgreSQL. Local ownership, encrypted SQLite and command CAS use the real runtime.
 * Account acquisition/verification, draft-root eligibility, vault keys and media providers are
 * explicitly synthetic TEST ports. No live login, OS keystore, media processing, publication,
 * native work, process-kill or physical disk-durability claim is made by this protocol suite.
 * No controller reply is manufactured from a direct store call. SQL below only seeds authority,
 * injects an explicit expiry/revocation fault, or reads independent durable-effect evidence. */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class PostDraftControllerHttpIntegrationTest {
    @Test fun actualPublicControllersCreateUpdateGetListAndConfirmDeleteThroughCanonicalHttp() = journey { j ->
        val local = j.newDraft("Dinner, handled 🌱", "Green bowl")
        assertTrue(local.localAcknowledged); assertNull(local.server); assertTrue(j.calls.isEmpty())
        val created = value(j.drafts.saveExplicitly())
        val first = assertNotNull(created.selected?.server)
        assertTrue(created.serverAcknowledged); assertNull(created.pending); assertEquals("\"1\"", first.etag)
        assertEquals(local.clientDraftId, first.clientDraftId)
        val createBody = parse(assertNotNull(j.calls.single().body))
        assertEquals(setOf("clientDraftId", "caption", "altText", "mediaIds", "audience", "keepOnPlate", "allowRecipeSaves"), createBody.keys)
        assertEquals("self", createBody.getValue("audience").jsonObject.text("kind"))
        assertTrue(createBody.getValue("mediaIds").jsonArray.isEmpty())
        assertEquals(JsonPrimitive(false), createBody["allowRecipeSaves"])

        value(j.drafts.editText("Dinner for two"))
        val updated = value(j.drafts.saveExplicitly())
        assertTrue(updated.serverAcknowledged); assertEquals("\"2\"", updated.selected?.server?.etag)
        assertEquals("Green bowl", updated.selected?.altText)
        assertEquals(setOf("caption", "altText"), parse(assertNotNull(j.calls.last().body)).keys)
        assertEquals("\"1\"", j.calls.last().ifMatch)
        // Natural Save -> Refresh must work; this GET still cannot acknowledge a new command.
        val observed = value(j.drafts.refreshRemote(first.id))
        assertFalse(observed.serverAcknowledged); assertEquals("Dinner for two", observed.selected?.server?.caption)
        val writes = j.session.localWriteStatements
        val listed = value(j.drafts.listRemote())
        assertEquals(listOf(first.id), listed.remoteItems.map { it.id }); assertFalse(listed.serverAcknowledged)
        assertEquals(writes, j.session.localWriteStatements, "Listing cannot persist a receipt or import a local draft.")
        value(j.drafts.openLocal(local.clientDraftId))
        val beforeConfirmation = j.calls.size
        val ticket = assertNotNull(value(j.drafts.prepareServerDiscard()).discardConfirmation)
        assertEquals(beforeConfirmation, j.calls.size)
        val removed = value(j.drafts.confirmServerDiscard(ticket))
        assertTrue(removed.serverAcknowledged); assertNull(removed.selected); assertTrue(removed.localDrafts.isEmpty())
        assertEquals(listOf("createPostDraft", "updatePostDraft", "getPostDraft", "listPostDrafts", "deletePostDraft"), j.calls.map { it.operation })
        assertEquals("\"2\"", j.calls.last().ifMatch); assertNull(j.calls.last().body)
        assertEquals(204, j.exchanges.last().status); assertNull(j.exchanges.last().body)
        assertEquals("discarded", j.fixture.value("SELECT status FROM platform.post_drafts"))
        assertEquals("{}", j.fixture.value("SELECT content::text FROM platform.post_drafts"))
        j.effects(drafts = 1, commands = 3, events = 3)
    }

    @Test fun actualUnknownCreateCommitSurvivesControllerReplacementAndReplaysOnlyOriginalRequest() = journey { j ->
        val local = j.newDraft("One original")
        j.fixture.faults.loseCommit = true
        val uncertain = value(j.drafts.saveExplicitly())
        assertFalse(uncertain.serverAcknowledged); assertNotNull(uncertain.pending)
        assertEquals(503, j.exchanges.single().status)
        assertEquals("OUTCOME_UNKNOWN", parse(assertNotNull(j.exchanges.single().body)).text("code"))
        val original = j.calls.single(); j.effects(1, 1, 1)
        j.replaceControllers()
        val restored = value(j.drafts.restoreLocal())
        assertTrue(restored.historical); assertFalse(restored.serverAcknowledged)
        assertEquals(original.key, restored.pending?.commandId); assertEquals(1, j.calls.size)
        value(j.drafts.openLocal(local.clientDraftId))
        val recovered = j.retryOriginal()
        assertTrue(recovered.serverAcknowledged); assertNull(recovered.pending)
        assertEquals(original, j.calls.last()); assertEquals(2, j.calls.size)
        assertEquals("One original", recovered.selected?.server?.caption)
        j.effects(1, 1, 1)
    }

    @Test fun matchingGetAfterLostCreateReplyIsObservationNotAckAndCannotReplaceOriginalIntent() = journey { j ->
        j.newDraft("Observed, not acknowledged")
        j.dropNextSuccessfulMutation = true
        val uncertain = value(j.drafts.saveExplicitly()); val pending = assertNotNull(uncertain.pending)
        val original = j.calls.single(); val firstReply = j.exchanges.single()
        assertEquals(201, firstReply.status); assertFalse(uncertain.serverAcknowledged)
        val id = parse(assertNotNull(firstReply.body)).text("id")
        val observed = value(j.drafts.refreshRemote(id))
        assertFalse(observed.serverAcknowledged); assertEquals(pending.commandId, observed.pending?.commandId)
        assertEquals("getPostDraft", j.calls.last().operation)
        assertIs<PortResult.Failure>(j.drafts.discardUnsent())
        val recovered = j.retryOriginal()
        assertTrue(recovered.serverAcknowledged); assertNull(recovered.pending)
        assertEquals(original, j.calls.last()); assertEquals(parse(assertNotNull(firstReply.body)), parse(assertNotNull(j.exchanges.last().body)))
        assertEquals(firstReply.etag, j.exchanges.last().etag)
        assertEquals(3, j.calls.size); j.effects(1, 1, 1)
    }

    @Test fun newerTypingDuringActualCommittedHttpReplyIsPreservedWhileOriginalRequestReconciles() = journey { j ->
        j.newDraft("Submitted text")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        j.afterNextSuccessfulMutation = { entered.complete(Unit); withContext(NonCancellable) { release.await() } }
        coroutineScope {
            val saving = async { j.drafts.saveExplicitly() }
            withTimeout(15_000) { entered.await() }
            val editing = async(start = CoroutineStart.UNDISPATCHED) { j.drafts.editText("Newer text") }
            try {
                assertEquals("Newer text", j.drafts.states.value.selected?.caption)
            } finally { release.complete(Unit) }
            assertEquals(FailureReason.STALE_SESSION, assertIs<PortResult.Failure>(saving.await()).reason)
            value(editing.await())
        }
        val original = j.calls.single()
        assertFalse(j.drafts.states.value.serverAcknowledged); assertNull(j.drafts.states.value.selected?.server)
        val recovered = j.retryOriginal()
        assertTrue(recovered.serverAcknowledged); assertEquals("Newer text", recovered.selected?.caption)
        assertEquals("Submitted text", recovered.selected?.server?.caption); assertFalse(assertNotNull(recovered.selected).textMatchesServer)
        assertEquals(original, j.calls.last()); assertEquals(2, j.calls.size); j.effects(1, 1, 1)
        val savedNewer = value(j.drafts.saveExplicitly())
        assertTrue(savedNewer.serverAcknowledged); assertTrue(assertNotNull(savedNewer.selected).textMatchesServer)
        assertEquals("Newer text", savedNewer.selected?.server?.caption)
        assertEquals("\"1\"", j.calls.last().ifMatch); assertNotEquals(original.key, j.calls.last().key)
        j.effects(1, 2, 2)
    }

    @Test fun originalUpdateReplyLossKeepsPatchKeyBytesAndExactIfMatchAcrossExplicitRetry() = journey { j ->
        j.newDraft("Before", "Keep description"); value(j.drafts.saveExplicitly())
        value(j.drafts.editText("Original patch")); j.dropNextSuccessfulMutation = true
        val uncertain = value(j.drafts.saveExplicitly()); assertFalse(uncertain.serverAcknowledged)
        val original = j.calls.last(); val committed = j.exchanges.last()
        assertEquals("updatePostDraft", original.operation); assertEquals("\"1\"", original.ifMatch)
        value(j.drafts.editText("Newer unsent patch"))
        val recovered = j.retryOriginal()
        assertTrue(recovered.serverAcknowledged); assertEquals("Newer unsent patch", recovered.selected?.caption)
        assertEquals("Original patch", recovered.selected?.server?.caption); assertEquals("\"2\"", recovered.selected?.server?.etag)
        assertEquals(original, j.calls.last()); assertEquals(parse(assertNotNull(committed.body)), parse(assertNotNull(j.exchanges.last().body)))
        assertEquals(3, j.calls.size); j.effects(1, 2, 2)
    }

    @Test fun expiredOwnedDraftCleanupUsesRetainedExactConsentWithoutContentOrNewWriteEligibility() = journey { j ->
        val local = j.newDraft("Past retention")
        val saved = value(j.drafts.saveExplicitly()); val server = assertNotNull(saved.selected?.server)
        j.fixture.expire(UUID.fromString(server.id))
        j.fixture.authority.enabled = false; j.fixture.authority.terms = false
        j.fixture.sql("UPDATE media_test.drafts SET eligible=false")
        val before = j.calls.size
        val ticket = assertNotNull(value(j.drafts.prepareServerDiscard()).discardConfirmation)
        assertEquals(before, j.calls.size); j.effects(1, 1, 1)
        val removed = value(j.drafts.confirmServerDiscard(ticket))
        assertTrue(removed.serverAcknowledged); assertTrue(removed.localDrafts.isEmpty())
        assertEquals(listOf("deletePostDraft"), j.calls.drop(before).map { it.operation })
        assertEquals(mapOf("draftId" to server.id), j.calls.last().path); assertEquals(server.etag, j.calls.last().ifMatch)
        assertEquals(204, j.exchanges.last().status); j.effects(1, 2, 2)
        assertEquals("discarded", j.fixture.value("SELECT status FROM platform.post_drafts"))
        assertIs<PortResult.Failure>(j.drafts.openLocal(local.clientDraftId))
    }

    @Test fun exactObservedUpdateResultCanReplayAfterOldBaselineExpiryWithoutRebasingItsOriginal() = journey(draftLifetimeSeconds = 120) { j ->
        j.newDraft("Initial baseline"); val first = assertNotNull(value(j.drafts.saveExplicitly()).selected?.server)
        val oldExpiry = Instant.parse(parse(first.document.encodeUtf8().decodeToString()).text("expiresAt")).toEpochMilli()
        value(j.drafts.editText("Committed successor")); j.dropNextSuccessfulMutation = true
        val uncertain = value(j.drafts.saveExplicitly()); val original = j.calls.last(); val committed = j.exchanges.last()
        assertNotNull(uncertain.pending); assertFalse(uncertain.serverAcknowledged)
        val nextExpiry = Instant.parse(parse(assertNotNull(committed.body)).text("expiresAt")).toEpochMilli()
        assertTrue(nextExpiry > oldExpiry + 1, "Actual updated draft must extend this fixture's expiry.")
        val observation = value(j.drafts.refreshRemote(first.id))
        assertFalse(observation.serverAcknowledged); assertEquals(original.key, observation.pending?.commandId)
        j.advanceTo(oldExpiry + 1)
        val replay = value(j.drafts.retryOriginal())
        assertTrue(replay.serverAcknowledged); assertNull(replay.pending); assertEquals("\"2\"", replay.selected?.server?.etag)
        assertEquals(original, j.calls.last()); assertEquals("\"1\"", j.calls.last().ifMatch)
        assertEquals(parse(assertNotNull(committed.body)), parse(assertNotNull(j.exchanges.last().body)))
        assertEquals(committed.etag, j.exchanges.last().etag); j.effects(1, 2, 2)
    }

    @Test fun lostDeleteCommitReplaysOriginalEmptyReceiptWithoutGetOrSecondDeletion() = journey { j ->
        j.newDraft("Remove exactly once"); value(j.drafts.saveExplicitly())
        val ticket = assertNotNull(value(j.drafts.prepareServerDiscard()).discardConfirmation)
        j.fixture.faults.loseCommit = true
        val uncertain = value(j.drafts.confirmServerDiscard(ticket)); assertFalse(uncertain.serverAcknowledged)
        assertNotNull(uncertain.pending); val original = j.calls.last()
        assertEquals("deletePostDraft", original.operation); assertNull(original.body)
        assertEquals(503, j.exchanges.last().status); assertEquals("discarded", j.fixture.value("SELECT status FROM platform.post_drafts"))
        val recovered = j.retryOriginal()
        assertTrue(recovered.serverAcknowledged); assertTrue(recovered.localDrafts.isEmpty()); assertNull(recovered.pending)
        assertEquals(original, j.calls.last()); assertEquals(204, j.exchanges.last().status); assertNull(j.exchanges.last().body)
        assertEquals(listOf("createPostDraft", "deletePostDraft", "deletePostDraft"), j.calls.map { it.operation })
        j.effects(1, 2, 2)
    }

    @Test fun actualSignedPageConflictClearsObservationAndNeverAcknowledgesOrWritesLocalState() = journey { j ->
        val saved = (1..3).map { j.newDraft("Plate $it"); assertNotNull(value(j.drafts.saveExplicitly()).selected?.server) }
        val writes = j.session.localWriteStatements
        val first = value(j.drafts.listRemote())
        assertEquals(2, first.remoteItems.size); assertTrue(first.hasMore); assertFalse(first.serverAcknowledged)
        assertNotNull(first.remoteHeadEtag); assertEquals(writes, j.session.localWriteStatements)
        // A concurrent owner's mutation also traverses real canonical HTTP, never direct store.
        val peer = j.peerPatch(saved.last().id, saved.last().etag, buildJsonObject { put("caption", "Other device edit") })
        assertEquals(200, peer.status)
        assertIs<PortResult.Failure>(j.drafts.nextRemotePage())
        val conflicted = j.drafts.states.value
        assertTrue(conflicted.remoteItems.isEmpty()); assertFalse(conflicted.hasMore); assertNull(conflicted.remoteHeadEtag)
        assertFalse(conflicted.serverAcknowledged); assertEquals(writes, j.session.localWriteStatements)
        assertEquals(409, j.exchanges.last().status)
        assertEquals("CURSOR_INVALID", parse(assertNotNull(j.exchanges.last().body)).text("code"))
        val refreshed = value(j.drafts.listRemote())
        assertEquals(2, refreshed.remoteItems.size); assertTrue(refreshed.hasMore); assertFalse(refreshed.serverAcknowledged)
        assertNotEquals(first.remoteHeadEtag, refreshed.remoteHeadEtag); j.effects(3, 4, 4)
    }

    @Test fun controllerTextPatchPreservesCanonicalFieldsItDoesNotOwnAfterRealHttpImport() = journey { j ->
        j.newDraft("Original", "Original alt"); val saved = value(j.drafts.saveExplicitly())
        val server = assertNotNull(saved.selected?.server)
        val circle = j.fixture.grant("circle")
        val peer = j.peerPatch(server.id, server.etag, buildJsonObject {
            put("keepOnPlate", true); put("saveDisclosureVersion", "save-policy-v1")
            put("audience", buildJsonObject { put("kind", "circles"); put("circleIds", buildJsonArray { add(circle.toString()) }) })
        })
        assertEquals(200, peer.status)
        value(j.drafts.refreshRemote(server.id))
        value(j.drafts.editText("Text-only update", ""))
        val updated = value(j.drafts.saveExplicitly())
        assertTrue(updated.serverAcknowledged); assertEquals("\"3\"", updated.selected?.server?.etag)
        val patch = parse(assertNotNull(j.calls.last().body)); assertEquals(setOf("caption", "altText"), patch.keys)
        assertEquals(JsonPrimitive(""), patch["altText"])
        val durable = parse(j.fixture.value("SELECT content::text FROM platform.post_drafts"))
        assertEquals(JsonPrimitive(true), durable["keepOnPlate"])
        assertEquals(JsonPrimitive("save-policy-v1"), durable["saveDisclosureVersion"])
        assertEquals(listOf(JsonPrimitive(circle.toString())), durable.getValue("audience").jsonObject.getValue("circleIds").jsonArray.toList())
        assertEquals(JsonPrimitive(false), durable["allowRecipeSaves"]); j.effects(1, 3, 3)
    }

    private fun journey(draftLifetimeSeconds: Int = 2_592_000, action: suspend (Journey) -> Unit) = runBlocking {
        newSingleThreadContext("feedme-post-draft-http-owner-test").use { owner ->
            val fixture = PostDraftTestFixture(cluster.database())
            val configuredStore = fixture.newStore(fixture.policy(lifetime = draftLifetimeSeconds))
            val configuration = PostDraftHttpConfiguration("test", configuredStore, SocialHttpVerifier { bearer ->
                bearer.token.use { if (it == TOKEN) PortResult.Value(fixture.account) else PortResult.Failure(FailureReason.UNAUTHENTICATED) }
            }, Dispatchers.IO)
            val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                feedMeLocalService(LocalServerConfig.fromEnvironment(emptyMap()), postDraft = configuration)
            }
            server.start(wait = false)
            try {
                val port = server.engine.resolvedConnectors().single().port
                withContext(owner) {
                    val scope = StorageScope("test", ActorKind.ACCOUNT, fixture.account.accountId.toString())
                    val session = PostDraftHttpSessionFixture.open(owner, StoredCredentials.Account(scope, SecretText(TOKEN), null,
                        Long.MAX_VALUE, SecretText(fixture.account.deviceSessionId.toString())))
                    var time = System.currentTimeMillis(); val clock = EpochClock { time }
                    val transport = FeedMeTransport.create(ApiEndpoint.loopback("test", port), session.access.credentials,
                        session.boundary, owner, clock, ConnectivityPort { Connectivity.ONLINE })
                    val j = Journey(fixture, session, owner, clock, transport) { next -> time = next }
                    try { action(j); session.requireNoNativeWork() }
                    finally { try { j.closeControllers() } finally { transport.close(); session.close() } }
                }
            } finally { server.stop(gracePeriodMillis = 100, timeoutMillis = 1000) }
        }
    }

    private class Journey(val fixture: PostDraftTestFixture, val session: PostDraftHttpSessionFixture,
        private val owner: CoroutineDispatcher, private val clock: EpochClock, val transport: FeedMeTransport,
        private val setTime: (Long) -> Unit) {
        val calls = mutableListOf<Request>()
        val exchanges = mutableListOf<Exchange>()
        var dropNextSuccessfulMutation = false
        var afterNextSuccessfulMutation: (suspend () -> Unit)? = null
        private val observed = object : AccountTransport {
            override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                val request = Request.from(call); calls += request
                val result = transport.execute(lease, call)
                val reply = (result as? PortResult.Value)?.value
                exchanges += Exchange(request, reply?.status, reply?.etag, reply?.body?.copyForCodec()?.decodeToString())
                if (call.operationId in MUTATIONS && reply != null && reply.status in 200..299) {
                    val after = afterNextSuccessfulMutation; afterNextSuccessfulMutation = null; after?.invoke()
                    if (dropNextSuccessfulMutation) { dropNextSuccessfulMutation = false; return PortResult.Failure(FailureReason.OUTCOME_UNKNOWN) }
                }
                return result
            }
        }
        private val access = AuthenticatedMealPlanningAccess.fromSession(session.access, observed)
        private val ids = MealOperationIds { UUID.randomUUID().toString() }
        private val online = ConnectivityPort { Connectivity.ONLINE }
        private var meals = newMeals()
        private var controllers = newControllers()
        val drafts: PostDraftController get() = checkNotNull(controllers.postDrafts)
        private fun newMeals() = MealRequestController(access, session.boundary, owner, clock, online, ids,
            MealFlowPolicy(86_400_000, 86_400_000, 4, 128))
        private fun newControllers() = MealKitchenControllers.createWithDrafts(access, session.boundary, owner, clock, online,
            ids, meals, meals.draftReadiness, CookingFlowPolicy(60_000, 262_144, 131_072), CookbookPolicy(2, 60_000),
            PostDraftClientPolicy(8, 1_048_576, 262_144, 128, 64, 2, 100, 60_000))
        suspend fun closeControllers() { value(drafts.close()); value(controllers.cooking.close()); value(controllers.cookbook.close()); value(meals.close()) }
        suspend fun replaceControllers() { closeControllers(); meals = newMeals(); controllers = newControllers() }
        suspend fun newDraft(caption: String, alt: String? = null): LocalPostDraft {
            val local = assertNotNull(value(drafts.newLocalDraft(caption, alt)).selected)
            // Explicit synthetic current root authority for the exact controller-allocated UUID.
            fixture.source.connection.use { c -> c.prepareStatement("INSERT INTO media_test.drafts VALUES(?,?,1,true)").use {
                it.setObject(1, fixture.account.accountId); it.setObject(2, UUID.fromString(local.clientDraftId)); it.executeUpdate()
            } }
            return local
        }
        suspend fun retryOriginal(): PostDraftState {
            setTime(clock.nowMillis() + 60_000)
            var state = value(drafts.retryOriginal())
            // An interrupted in-flight dispatch first persists recovery/backoff without sending.
            if (!state.serverAcknowledged && state.earliestRetryAtMillis != null) {
                setTime(maxOf(clock.nowMillis(), checkNotNull(state.earliestRetryAtMillis)) + 1)
                state = value(drafts.retryOriginal())
            }
            return state
        }
        fun advanceTo(epochMillis: Long) { require(epochMillis >= clock.nowMillis()); setTime(epochMillis) }
        suspend fun peerPatch(id: String, etag: String, body: JsonObject): ApiReply = value(transport.execute(session.access.lease,
            ApiCall("updatePostDraft", mapOf("draftId" to id), body = PrivateBytes(body.toString().encodeToByteArray()),
                idempotencyKey = SecretText(UUID.randomUUID().toString()), ifMatch = etag)))
        fun effects(drafts: Int, commands: Int, events: Int) {
            assertEquals(drafts, fixture.count("platform.post_drafts")); assertEquals(commands, fixture.count("platform.idempotency"))
            assertEquals(events, fixture.count("platform.outbox"))
            assertEquals(0, fixture.count("platform.media_assets")); assertEquals(0, fixture.mediaFixture.signer.authorizations.size)
        }
    }

    /** Immutable diagnostic snapshots of synthetic test traffic, never used to provide a reply. */
    private data class Request(val operation: String, val path: Map<String, String>, val query: Map<String, List<String>>,
        val key: String?, val ifMatch: String?, val body: String?) {
        companion object { fun from(call: ApiCall) = Request(call.operationId, call.pathParameters.toMap(),
            call.queryParameters.mapValues { it.value.toList() }, call.idempotencyKey?.use { it }, call.ifMatch,
            call.body?.copyForCodec()?.decodeToString()) }
    }
    private data class Exchange(val request: Request, val status: Int?, val etag: String?, val body: String?)
    companion object {
        @JvmField @ClassRule val timeout: Timeout = Timeout.seconds(240)
        private lateinit var cluster: PostgresTestCluster
        private const val TOKEN = "synthetic-post-draft-controller"
        private val MUTATIONS = setOf("createPostDraft", "updatePostDraft", "deletePostDraft")
        @JvmStatic @BeforeClass fun start() { cluster = PostgresTestCluster.start() }
        @JvmStatic @AfterClass fun stop() { cluster.close() }
        private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject
        private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
        private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
    }
}
