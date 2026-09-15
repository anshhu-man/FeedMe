package com.feedme.server.http

import com.feedme.contracts.WireDocument
import com.feedme.core.ports.*
import com.feedme.kitchen.*
import com.feedme.server.config.LocalServerConfig
import com.feedme.server.cooking.CookingTestFixture
import com.feedme.server.db.PostgresTestCluster
import com.feedme.sync.CommandPhase
import com.feedme.transport.ApiEndpoint
import com.feedme.transport.FeedMeTransport
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.junit.rules.Timeout
import kotlin.test.*

/**
 * Real shared transport, canonical binding, cooking repository/queue, CIO and PostgreSQL.
 * Credential verification/editorial inputs and atomic memory CAS are explicit TEST fixtures.
 * This is not native persistence, provider authentication, timer delivery or offline-manifest proof.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class CookingClientServerIntegrationTest {
    @Test fun realTransportDownloadsExactPinAndSynchronizesOrderedProgressThenExplicitCompletion() = journey { j ->
        val created = j.start(); val id = created.string("id")
        var state = value(j.kitchen.cooking.download(j.lease, id))
        assertEquals(CookingAvailability.AVAILABLE, state.availability)
        assertEquals(j.plan.string("id"), state.plan.id.value); assertEquals("mix", state.progress.currentStepId)
        assertEquals("\"1\"", state.etag)
        val move = UUID.randomUUID().toString()
        state = value(j.kitchen.cooking.edit(j.lease, id, state.localRevision, move, CookingEdit.MoveTo("serve")))
        val mark = UUID.randomUUID().toString()
        state = value(j.kitchen.cooking.edit(j.lease, id, state.localRevision, mark, CookingEdit.MarkStepComplete("mix")))
        assertEquals(listOf(move, mark), state.pendingCommandIds)
        assertEquals("active", j.remote(id).string("status")); assertEquals("mix", j.remote(id).string("currentStepId"))
        state = j.sync(id, move)
        assertEquals(listOf(mark), state.pendingCommandIds); assertEquals("\"2\"", state.etag)
        state = j.sync(id, mark); assertTrue(state.pendingCommandIds.isEmpty()); assertEquals("\"3\"", state.etag)
        assertEquals("active", j.remote(id).string("status"), "Viewing/marking steps must not complete.")
        val finish = UUID.randomUUID().toString()
        state = value(j.kitchen.cooking.edit(j.lease, id, state.localRevision, finish, CookingEdit.Complete(false)))
        assertEquals(CookingStatus.COMPLETED, state.progress.status)
        assertEquals("active", j.remote(id).string("status")); assertEquals(listOf(finish), state.pendingCommandIds)
        state = j.sync(id, finish)
        assertEquals(CookingStatus.COMPLETED, state.progress.status); assertEquals("completed", state.remote.status)
        assertEquals("\"4\"", state.etag); assertTrue(state.pendingCommandIds.isEmpty())
        assertEquals(4, j.fixture.count("cooking.step_events"))
        assertEquals(1, j.fixture.value("SELECT count(*) FROM platform.outbox WHERE event_type='cooking.session.completed.v1'").toInt())
        assertEquals(listOf("createCookSession", "updateCookSession", "updateCookSession", "completeCookSession"),
            j.mutations.map { it.operationId })
        assertNull(j.mutations.last().ifMatch)
        assertEquals(listOf("\"1\"", "\"2\""), j.mutations.filter { it.operationId == "updateCookSession" }.map { it.ifMatch })
    }

    @Test fun remoteRefreshPreservesUnacknowledgedLocalCommandAndConflictingProgress() = journey { j ->
        val id = j.start().string("id"); var state = value(j.kitchen.cooking.download(j.lease, id))
        val pending = UUID.randomUUID().toString()
        state = value(j.kitchen.cooking.edit(j.lease, id, state.localRevision, pending, CookingEdit.MoveTo("serve")))
        val second = j.fixture.secondDevice()
        CookingTestFixture.commandReply(j.fixture.store.updateCookSession(second, UUID.randomUUID(), UUID.fromString(id), "\"1\"",
            buildJsonObject { put("deviceSequence", 1); put("currentStepId", "mix"); put("status", "paused") }))
        val refreshed = value(j.kitchen.cooking.download(j.lease, id))
        assertEquals(CookingAvailability.CONFLICT, refreshed.availability)
        assertEquals(listOf(pending), refreshed.pendingCommandIds)
        assertEquals("serve", refreshed.progress.currentStepId)
        assertEquals("mix", refreshed.conflictingRemote?.currentStepId?.value)
        assertEquals("active", refreshed.remote.status)
        assertEquals("paused", refreshed.conflictingRemote?.status)
        assertEquals(2, j.fixture.count("cooking.step_events"))
        assertEquals(1, j.mutations.size, "A GET is not dispatch or an acknowledgement of the pending edit.")
    }

    @Test fun actualRecalledHttpProblemCreatesLocalBlockingEvidenceWithoutSendingProgress() = journey { j ->
        val id = j.start().string("id"); val original = value(j.kitchen.cooking.download(j.lease, id))
        j.fixture.changeRecipe { JsonObject(it + mapOf("version" to JsonPrimitive(2), "reviewStatus" to JsonPrimitive("recalled"),
            "recallReasonCode" to JsonPrimitive("safetyReview"))) }
        assertIs<PortResult.Failure>(j.kitchen.cooking.download(j.lease, id))
        val blocked = assertNotNull(value(j.kitchen.cooking.read(j.lease, id)))
        assertEquals(CookingAvailability.RECALLED, blocked.availability)
        assertTrue(value(j.kitchen.cooking.hasRecipeRecall(j.lease, CookingTestFixture.VERSION)))
        assertIs<PortResult.Failure>(j.kitchen.cooking.edit(j.lease, id, blocked.localRevision, UUID.randomUUID().toString(), CookingEdit.MoveTo("serve")))
        assertEquals(original.progress.currentStepId, blocked.progress.currentStepId)
        assertEquals(1, j.fixture.count("cooking.step_events")); assertEquals(1, j.mutations.size)
        j.boundary.clear()
        assertEquals(FailureReason.STALE_SESSION, assertIs<PortResult.Failure>(j.kitchen.cooking.read(j.lease, id)).reason)
    }

    private fun journey(action: suspend (Journey) -> Unit) = runBlocking {
        newSingleThreadContext("feedme-cooking-http-owner-test").use { owner ->
            val f = CookingTestFixture(cluster.database()); val plan = f.seedPlan()
            val cooking = CookingHttpConfiguration("test", f.store, CookingHttpVerifier { bearer ->
                bearer.token.use { if (it == "synthetic-client") PortResult.Value(f.account) else PortResult.Failure(FailureReason.UNAUTHENTICATED) }
            }, Dispatchers.IO)
            val planning = PlanningHttpConfiguration("test", f.plans, PlanningHttpVerifier { bearer ->
                bearer.token.use { if (it == "synthetic-client") PortResult.Value(f.planningActor(f.account)) else PortResult.Failure(FailureReason.UNAUTHENTICATED) }
            }, Dispatchers.IO)
            val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                feedMeLocalService(LocalServerConfig.fromEnvironment(emptyMap()), cooking = cooking, planning = planning)
            }
            server.start(wait = false)
            try {
                val port = server.engine.resolvedConnectors().single().port
                withContext(owner) {
                    val scope = StorageScope("test", ActorKind.ACCOUNT, f.account.principalId.toString())
                    val boundary = SessionBoundary(); val lease = boundary.activate(scope)
                    val clock = EpochClock { System.currentTimeMillis() }
                    val credentials = object : SecureCredentialStore {
                        override suspend fun read(scope: StorageScope): PortResult<StoredCredentials?> {
                            check(scope == lease.scope)
                            return PortResult.Value(StoredCredentials.Account(scope, SecretText("synthetic-client"), null,
                                clock.nowMillis() + 60_000, SecretText(f.account.deviceSessionId.toString())))
                        }
                        override suspend fun replace(scope: StorageScope, credentials: StoredCredentials): PortResult<Unit> = error("No credential write in protocol test")
                        override suspend fun erase(scope: StorageScope): PortResult<Unit> = error("No credential erase in protocol test")
                    }
                    val transport = FeedMeTransport.create(ApiEndpoint.loopback("test", port), credentials, boundary, owner, clock, ConnectivityPort { Connectivity.ONLINE })
                    val mutations = mutableListOf<ApiCall>()
                    val observed = object : AccountTransport {
                        override suspend fun execute(lease: SessionLease, call: ApiCall): PortResult<ApiReply> {
                            if (call.operationId in setOf("createCookSession", "updateCookSession", "completeCookSession")) mutations += call
                            return transport.execute(lease, call)
                        }
                    }
                    val kitchen = PrivateKitchenSession(scope, AtomicMemoryFixture(scope), boundary, owner, clock, observed, UUID.randomUUID().toString())
                    try { action(Journey(f, plan, transport, observed, kitchen, boundary, lease, mutations)) }
                    finally { boundary.clear(); transport.close() }
                }
            } finally { server.stop(gracePeriodMillis = 100, timeoutMillis = 1000) }
        }
    }

    private class Journey(val fixture: CookingTestFixture, val plan: JsonObject, val transport: FeedMeTransport,
        val observed: AccountTransport, val kitchen: PrivateKitchenSession, val boundary: SessionBoundary,
        val lease: SessionLease, val mutations: List<ApiCall>) {
        suspend fun start(): JsonObject {
            val body = buildJsonObject { put("planId", plan.string("id")); put("deviceSequence", 0) }
            val reply = value(observed.execute(lease, ApiCall("createCookSession", body = PrivateBytes(body.toString().encodeToByteArray()),
                idempotencyKey = SecretText(UUID.randomUUID().toString()))))
            assertEquals(201, reply.status); return parse(checkNotNull(reply.body).copyForCodec().decodeToString())
        }
        suspend fun remote(id: String): JsonObject {
            val reply = value(transport.execute(lease, ApiCall("getCookSession", mapOf("sessionId" to id))))
            assertEquals(200, reply.status); return parse(checkNotNull(reply.body).copyForCodec().decodeToString())
        }
        suspend fun sync(id: String, command: String): CookingSnapshot {
            val pending = assertNotNull(value(kitchen.cooking.materializeNext(lease, id)))
            assertEquals(command, pending.commandId)
            val sent = assertNotNull(value(kitchen.commands.dispatchConfirmed(lease, command)))
            assertEquals(CommandPhase.RECEIPT_READY, sent.phase)
            return value(kitchen.cooking.applyReceipt(lease, id))
        }
    }

    /** Deterministic atomic CAS fixture only; no native durability or encryption assertion. */
    private class AtomicMemoryFixture(private val owner: StorageScope) : PrivateStateStore {
        private val rows = mutableMapOf<RecordKey, PrivateRecord>(); private val clocks = mutableMapOf<RecordKey, Long>()
        override suspend fun read(scope: StorageScope, key: RecordKey): PortResult<PrivateRecord?> {
            check(scope == owner); return PortResult.Value(rows[key])
        }
        override suspend fun commit(scope: StorageScope, mutations: List<StoreMutation>): PortResult<Map<RecordKey, Long?>> {
            check(scope == owner); check(mutations.isNotEmpty() && mutations.map { it.key }.distinct().size == mutations.size)
            if (mutations.any { rows[it.key]?.revision != it.expectedRevision }) return PortResult.Failure(FailureReason.CONFLICT)
            val receipts = linkedMapOf<RecordKey, Long?>()
            for (mutation in mutations) {
                val revision = Math.addExact(clocks[mutation.key] ?: 0, 1); clocks[mutation.key] = revision
                when (mutation) {
                    is StoreMutation.Put -> { rows[mutation.key] = PrivateRecord(revision, mutation.schemaVersion, PrivateBytes(mutation.payload.copyForCodec())); receipts[mutation.key] = revision }
                    is StoreMutation.Delete -> { rows.remove(mutation.key); receipts[mutation.key] = null }
                }
            }
            return PortResult.Value(receipts)
        }
        override suspend fun eraseScope(scope: StorageScope): PortResult<Unit> = error("No scope erasure in cooking protocol test")
    }
    companion object {
        @JvmField @ClassRule val timeout: Timeout = Timeout.seconds(180)
        private lateinit var cluster: PostgresTestCluster
        @JvmStatic @BeforeClass fun start() { cluster = PostgresTestCluster.start() }
        @JvmStatic @AfterClass fun stop() { cluster.close() }
        private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject
        private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
        private fun <T> value(result: PortResult<T>): T = assertIs<PortResult.Value<T>>(result).value
    }
}
