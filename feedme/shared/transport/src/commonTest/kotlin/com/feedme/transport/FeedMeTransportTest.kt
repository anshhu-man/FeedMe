package com.feedme.transport

import com.feedme.core.ports.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class FeedMeTransportTest {
    private val uuid = "123e4567-e89b-12d3-a456-426614174000"
    private val healthBody = """{"status":"healthy","serverTime":"2026-09-13T00:00:00Z","minimumAppVersion":"1.0.0"}"""
    private val problemBody = """{"type":"https://example.test/problems/not-ready","title":"Not ready","status":503,"code":"NOT_READY","traceId":"$uuid"}"""
    private val scope = StorageScope("test", ActorKind.ACCOUNT, "actor")
    private val recipe = ApiCall("getRecipeVersion", mapOf("recipeId" to uuid, "recipeVersionId" to uuid))
    private fun account(device: Boolean = true, expires: Long = 2000) = StoredCredentials.Account(
        scope, SecretText("secret-token"), null, expires, if (device) SecretText(uuid) else null)
    private fun mutation() = ApiCall("createCookSession", body = PrivateBytes("""{"planId":"$uuid"}""".encodeToByteArray()), idempotencyKey = SecretText(uuid))

    private class Store(var value: StoredCredentials?) : SecureCredentialStore {
        var reads = 0
        var wait: CompletableDeferred<Unit>? = null
        var failure = false
        override suspend fun read(scope: StorageScope): PortResult<StoredCredentials?> {
            reads++; wait?.await()
            return if (failure) PortResult.Failure(FailureReason.UNAVAILABLE) else PortResult.Value(value)
        }
        override suspend fun replace(scope: StorageScope, credentials: StoredCredentials): PortResult<Unit> = error("No credential writes permitted")
        override suspend fun erase(scope: StorageScope): PortResult<Unit> = error("No credential writes permitted")
    }

    private fun TestScope.transport(store: Store, boundary: SessionBoundary, engine: MockEngine,
        connectivity: Connectivity = Connectivity.ONLINE) = FeedMeTransport(ApiEndpoint.loopback("test", 8789),
        store, boundary, StandardTestDispatcher(testScheduler), EpochClock { 1000 }, ConnectivityPort { connectivity },
        HttpClient(engine) { expectSuccess = false; followRedirects = false })

    @Test fun publicCallsNeverReadOrAttachStoredCredentials() = runTest {
        val store = Store(account())
        val engine = MockEngine { request ->
            assertNull(request.headers["Authorization"]); assertNull(request.headers["X-Device-Session"])
            assertEquals("identity", request.headers["Accept-Encoding"])
            assertEquals("http://127.0.0.1:8789/v1/health", request.url.toString())
            respond(healthBody, headers = headersOf("Content-Type", "application/json"))
        }
        val t = transport(store, SessionBoundary(), engine)
        try { assertIs<PortResult.Value<ApiReply>>(t.executePublic(ApiCall("getServiceHealth"))); assertEquals(0, store.reads) }
        finally { t.close(); engine.close() }
    }

    @Test fun accountHeadersAndProblemBodySurviveWithoutExceptionMapping() = runTest {
        val body = problemBody
        val engine = MockEngine { request ->
            assertEquals("Bearer secret-token", request.headers["Authorization"])
            assertEquals(uuid, request.headers["X-Device-Session"])
            respond(body, HttpStatusCode.ServiceUnavailable, headersOf("Content-Type" to listOf("application/problem+json; charset=UTF-8"),
                "Retry-After" to listOf("5"), "X-Trace-Id" to listOf(uuid)))
        }
        val boundary = SessionBoundary(); val lease = boundary.activate(scope)
        val t = transport(Store(account()), boundary, engine)
        try {
            val reply = assertIs<PortResult.Value<ApiReply>>(t.execute(lease, recipe)).value
            assertEquals(503, reply.status); assertEquals(5, reply.retryAfterSeconds)
            assertEquals(body, reply.body!!.copyForCodec().decodeToString())
            assertEquals("application/problem+json; charset=UTF-8", reply.contentType)
            assertFalse(reply.toString().contains("NOT_READY"))
        } finally { t.close(); engine.close() }
    }

    @Test fun guestCredentialsOmitDeviceAndBootstrapDoesToo() = runTest {
        val boundary = SessionBoundary(); val store = Store(account(false))
        var requests = 0
        val engine = MockEngine { request ->
            requests++; assertNull(request.headers["X-Device-Session"])
            assertEquals(if (requests == 1) "Bearer secret-token" else "Bearer guest-token", request.headers["Authorization"])
            respond(problemBody, HttpStatusCode.ServiceUnavailable, headersOf("Content-Type", "application/problem+json"))
        }
        val t = transport(store, boundary, engine)
        try {
            val bootstrap = ApiCall("bootstrapAccount",
                body = PrivateBytes("""{"installationId":"synthetic-installation-01","platform":"android","appVersion":"1.0.0"}""".encodeToByteArray()),
                idempotencyKey = SecretText(uuid))
            assertIs<PortResult.Value<ApiReply>>(t.execute(boundary.activate(scope), bootstrap))
            val guest = StorageScope("test", ActorKind.GUEST, "guest")
            store.value = StoredCredentials.Guest(guest, SecretText(uuid), SecretText("guest-token"), 2000)
            assertIs<PortResult.Value<ApiReply>>(t.execute(boundary.activate(guest), recipe))
            assertEquals(2, requests)
        } finally { t.close(); engine.close() }
    }

    @Test fun invalidExpiredMissingWrongScopeAndDemoSessionsNeverSend() = runTest {
        val boundary = SessionBoundary(); val store = Store(account())
        val engine = MockEngine { error("No dispatch allowed") }
        val t = transport(store, boundary, engine)
        try {
            val lease = boundary.activate(scope)
            store.value = null
            assertEquals(FailureReason.UNAUTHENTICATED, assertIs<PortResult.Failure>(t.execute(lease, recipe)).reason)
            store.value = account(expires = 1000)
            assertEquals(FailureReason.UNAUTHENTICATED, assertIs<PortResult.Failure>(t.execute(lease, recipe)).reason)
            store.value = account(false)
            assertEquals(FailureReason.INVALID_DATA, assertIs<PortResult.Failure>(t.execute(lease, recipe)).reason)
            store.value = account()
            val wrong = boundary.activate(StorageScope("test", ActorKind.ACCOUNT, "another"))
            assertEquals(FailureReason.UNAUTHENTICATED, assertIs<PortResult.Failure>(t.execute(wrong, recipe)).reason)
            val foreign = boundary.activate(StorageScope("prod", ActorKind.ACCOUNT, "actor"))
            assertEquals(FailureReason.INVALID_DATA, assertIs<PortResult.Failure>(t.execute(foreign, recipe)).reason)
            val demo = boundary.activate(StorageScope("test", ActorKind.DEMO, "demo"))
            assertEquals(FailureReason.UNAUTHENTICATED, assertIs<PortResult.Failure>(t.execute(demo, recipe)).reason)
            assertEquals(FailureReason.STALE_SESSION, assertIs<PortResult.Failure>(t.execute(lease, recipe)).reason)
        } finally { t.close(); engine.close() }
    }

    @Test fun accountSwitchDuringSecureReadStopsDispatch() = runTest {
        val boundary = SessionBoundary(); val store = Store(account()); store.wait = CompletableDeferred()
        val engine = MockEngine { error("Must not send old-owner credentials") }
        val t = transport(store, boundary, engine)
        try {
            val lease = boundary.activate(scope)
            val result = async { t.execute(lease, recipe) }; runCurrent()
            boundary.clear(); store.wait!!.complete(Unit)
            assertEquals(FailureReason.STALE_SESSION, assertIs<PortResult.Failure>(result.await()).reason)
        } finally { t.close(); engine.close() }
    }

    @Test fun accountSwitchAfterDispatchSuppressesResult() = runTest {
        val gate = CompletableDeferred<Unit>(); val started = CompletableDeferred<Unit>()
        val engine = MockEngine {
            started.complete(Unit); gate.await()
            respond(problemBody, HttpStatusCode.ServiceUnavailable, headersOf("Content-Type", "application/problem+json"))
        }
        val boundary = SessionBoundary(); val t = transport(Store(account()), boundary, engine)
        try {
            val lease = boundary.activate(scope); val result = async { t.execute(lease, recipe) }
            started.await(); boundary.clear(); gate.complete(Unit)
            assertEquals(FailureReason.STALE_SESSION, assertIs<PortResult.Failure>(result.await()).reason)
        } finally { t.close(); engine.close() }
    }

    @Test fun lostOrInvalidWriteReceiptIsUnknownAndSameKeyIsPreserved() = runTest {
        var requests = 0
        val engine = MockEngine { request ->
            requests++; assertEquals(uuid, request.headers["Idempotency-Key"])
            if (requests == 1) throw IllegalStateException("private-network-detail")
            respond("not-json", HttpStatusCode.Created, headersOf("Content-Type", "application/json"))
        }
        val boundary = SessionBoundary(); val t = transport(Store(account()), boundary, engine)
        try {
            val lease = boundary.activate(scope)
            repeat(2) { assertEquals(FailureReason.OUTCOME_UNKNOWN, assertIs<PortResult.Failure>(t.execute(lease, mutation())).reason) }
            assertEquals(2, requests)
        } finally { t.close(); engine.close() }
    }

    @Test fun offlineAndStorageFailureAreLocal() = runTest {
        val boundary = SessionBoundary(); val store = Store(account()); val engine = MockEngine { error("No dispatch") }
        val t = transport(store, boundary, engine, Connectivity.OFFLINE)
        try {
            assertEquals(FailureReason.OFFLINE, assertIs<PortResult.Failure>(t.execute(boundary.activate(scope), recipe)).reason)
            assertEquals(0, store.reads)
        } finally { t.close(); engine.close() }
        val second = MockEngine { error("No dispatch") }; store.failure = true
        val t2 = transport(store, boundary, second)
        try { assertEquals(FailureReason.STORAGE_FAILURE, assertIs<PortResult.Failure>(t2.execute(boundary.activate(scope), recipe)).reason) }
        finally { t2.close(); second.close() }
    }

    @Test fun cancellationPropagatesAndClosedTransportDoesNotSend() = runTest {
        val engine = MockEngine { throw CancellationException("cancelled") }
        val t = transport(Store(account()), SessionBoundary(), engine)
        try { assertFailsWith<CancellationException> { t.executePublic(ApiCall("getServiceHealth")) } }
        finally { t.close(); engine.close() }
        assertEquals(FailureReason.NOT_CONFIGURED, assertIs<PortResult.Failure>(t.executePublic(ApiCall("getServiceHealth"))).reason)
    }

    @Test fun trustedOriginsRejectUserInfoPathsPortsAndDowngrades() {
        for (origin in listOf("http://example.com", "https://u:p@example.com", "https://example.com/", "https://example.com?q=x",
            "https://example.com#x", "https://example.com:0", "https://example.com:65536", "https://a..com", "https://-a.com"))
            assertFailsWith<IllegalArgumentException> { ApiEndpoint.https("prod", origin) }
        assertNotNull(ApiEndpoint.https("prod", "https://api.example.com:443"))
        assertFailsWith<IllegalArgumentException> { ApiEndpoint.loopback("test", 0) }
    }

    @Test fun closeCancelsInFlightOwnedClientWork() = runTest {
        val started = CompletableDeferred<Unit>(); val never = CompletableDeferred<Unit>()
        val engine = MockEngine { started.complete(Unit); never.await(); respond(healthBody, headers = headersOf("Content-Type", "application/json")) }
        val t = transport(Store(account()), SessionBoundary(), engine)
        try {
            val result = async { t.executePublic(ApiCall("getServiceHealth")) }
            started.await(); t.close()
            assertFailsWith<CancellationException> { result.await() }
        } finally { t.close(); engine.close() }
    }
}
