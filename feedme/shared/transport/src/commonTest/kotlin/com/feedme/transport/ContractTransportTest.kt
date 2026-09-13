package com.feedme.transport

import com.feedme.core.ports.ActorKind
import com.feedme.core.ports.ApiCall
import com.feedme.core.ports.ApiReply
import com.feedme.core.ports.Connectivity
import com.feedme.core.ports.ConnectivityPort
import com.feedme.core.ports.EpochClock
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.SecretText
import com.feedme.core.ports.SecureCredentialStore
import com.feedme.core.ports.SessionBoundary
import com.feedme.core.ports.StorageScope
import com.feedme.core.ports.StoredCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Schema and response-binding checks at the public adapter, beyond the raw exchange's JSON guard. */
class ContractTransportTest {
    @Test fun missingRequiredAndUnexpectedCookStartFieldsFailBeforeSecureReadOrDispatch() = runTest {
        val scope = StorageScope("test", ActorKind.ACCOUNT, "synthetic-account")
        val store = Store(StoredCredentials.Account(scope, SecretText("synthetic-token"), null, 2000, SecretText(UUID)))
        val boundary = SessionBoundary()
        val lease = boundary.activate(scope)
        var requests = 0
        val engine = MockEngine { requests++; error("Invalid requests must not dispatch") }
        withTransport(engine, store, boundary) { transport ->
            for (body in listOf("{}", """{"planId":"$UUID","unexpected":true}""")) {
                val call = ApiCall("createCookSession", body = PrivateBytes(body.encodeToByteArray()), idempotencyKey = SecretText(UUID))
                assertEquals(PortResult.Failure(FailureReason.INVALID_DATA), transport.execute(lease, call))
            }
            assertEquals(0, store.reads, "Schema rejection must precede the secure-store suspension")
            assertEquals(0, requests)
        }
    }

    @Test fun shortPublicGuestNonceFailsBeforeDispatchOrSecureRead() = runTest {
        val store = Store()
        var requests = 0
        val engine = MockEngine { requests++; error("A short nonce must not dispatch") }
        withTransport(engine, store) { transport ->
            val body = """{"installationNonce":"${"n".repeat(31)}"}"""
            assertEquals(PortResult.Failure(FailureReason.INVALID_DATA), transport.executePublic(guestCall(body)))
            assertEquals(0, requests)
            assertEquals(0, store.reads)
        }
    }

    @Test fun unknownHealthStatusIsInvalidDataDespiteValidJson() = runTest {
        assertInvalidHealth(HEALTH_BODY.replace("\"healthy\"", "\"unknown-status\""))
    }

    @Test fun missingRequiredHealthFieldIsInvalidDataDespiteValidJson() = runTest {
        assertInvalidHealth("""{"status":"healthy","serverTime":"2026-09-13T00:00:00Z"}""")
    }

    @Test fun unexpectedHealthFieldIsInvalidDataDespiteValidJson() = runTest {
        assertInvalidHealth(HEALTH_BODY.dropLast(1) + ",\"future\":null}")
    }

    @Test fun schemaInvalidCreatedWriteReceiptLeavesTheOutcomeUnknown() = runTest {
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            assertEquals(UUID, request.headers["Idempotency-Key"])
            respond("{}", HttpStatusCode.Created, headersOf("Content-Type", "application/json"))
        }
        withTransport(engine) { transport ->
            assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), transport.executePublic(guestCall()))
            assertEquals(1, requests, "The invalid receipt must not trigger a retry")
        }
    }

    @Test fun schemaValidProblemWithDifferentHttpStatusRejectsReadsAndLeavesWritesUnknown() = runTest {
        assertRejectedProblem(problemBody(status = "500"), UUID)
    }

    @Test fun schemaValidProblemWithDifferentTraceHeaderRejectsReadsAndLeavesWritesUnknown() = runTest {
        assertRejectedProblem(problemBody(), "00000000-0000-4000-8000-000000000099")
    }

    @Test fun decimalSpellingOfMatchingProblemStatusIsAcceptedWithOriginalBytes() = runTest {
        val body = " \n" + problemBody(status = "503.0") + "\t\n"
        var requests = 0
        val store = Store()
        val engine = MockEngine {
            requests++
            respond(body, HttpStatusCode.ServiceUnavailable, headersOf(
                "Content-Type" to listOf("application/problem+json; charset=UTF-8"),
                "X-Trace-Id" to listOf(UUID), "Retry-After" to listOf("5"),
            ))
        }
        withTransport(engine, store) { transport ->
            for (call in listOf(ApiCall("getServiceHealth"), guestCall())) {
                val reply = assertIs<PortResult.Value<ApiReply>>(transport.executePublic(call)).value
                assertEquals(503, reply.status)
                assertEquals(UUID, reply.traceId)
                assertEquals(5L, reply.retryAfterSeconds)
                assertEquals("application/problem+json; charset=UTF-8", reply.contentType)
                assertContentEquals(body.encodeToByteArray(), reply.body!!.copyForCodec())
            }
            assertEquals(2, requests)
            assertEquals(0, store.reads)
        }
    }

    @Test fun validGuestRequestAndCreatedSessionCompleteWithoutChangingTheirBytes() = runTest {
        var requests = 0
        val store = Store()
        val engine = MockEngine { request ->
            requests++
            assertEquals("/v1/guest-sessions", request.url.encodedPath)
            assertEquals("POST", request.method.value)
            assertEquals(UUID, request.headers["Idempotency-Key"])
            assertNull(request.headers["Authorization"])
            assertContentEquals(GUEST_REQUEST_BODY.encodeToByteArray(), assertIs<OutgoingContent.ByteArrayContent>(request.body).bytes())
            respond(GUEST_SESSION_BODY, HttpStatusCode.Created, headersOf("Content-Type", "application/json"))
        }
        withTransport(engine, store) { transport ->
            val reply = assertIs<PortResult.Value<ApiReply>>(transport.executePublic(guestCall())).value
            assertEquals(201, reply.status)
            assertContentEquals(GUEST_SESSION_BODY.encodeToByteArray(), reply.body!!.copyForCodec())
            assertEquals(1, requests)
            assertEquals(0, store.reads)
        }
    }

    private suspend fun TestScope.assertInvalidHealth(body: String) {
        var requests = 0
        val store = Store()
        val engine = MockEngine {
            requests++
            respond(body, headers = headersOf("Content-Type", "application/json"))
        }
        withTransport(engine, store) { transport ->
            assertEquals(PortResult.Failure(FailureReason.INVALID_DATA), transport.executePublic(ApiCall("getServiceHealth")))
            assertEquals(1, requests)
            assertEquals(0, store.reads)
        }
    }

    private suspend fun TestScope.assertRejectedProblem(body: String, traceHeader: String) {
        var requests = 0
        val store = Store()
        val engine = MockEngine {
            requests++
            respond(body, HttpStatusCode.ServiceUnavailable, headersOf(
                "Content-Type" to listOf("application/problem+json"), "X-Trace-Id" to listOf(traceHeader),
            ))
        }
        withTransport(engine, store) { transport ->
            assertEquals(PortResult.Failure(FailureReason.INVALID_DATA), transport.executePublic(ApiCall("getServiceHealth")))
            assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), transport.executePublic(guestCall()))
            assertEquals(2, requests, "Only the two explicit calls may dispatch")
            assertEquals(0, store.reads)
        }
    }

    private suspend fun TestScope.withTransport(
        engine: MockEngine,
        store: Store = Store(),
        boundary: SessionBoundary = SessionBoundary(),
        block: suspend (FeedMeTransport) -> Unit,
    ) {
        val transport = FeedMeTransport(ApiEndpoint.loopback("test", 8789), store, boundary,
            StandardTestDispatcher(testScheduler), EpochClock { 1000 }, ConnectivityPort { Connectivity.ONLINE },
            HttpClient(engine) { expectSuccess = false; followRedirects = false })
        try { block(transport) } finally { transport.close(); engine.close() }
    }

    private class Store(private val credentials: StoredCredentials? = null) : SecureCredentialStore {
        var reads = 0
        override suspend fun read(scope: StorageScope): PortResult<StoredCredentials?> {
            reads++
            return PortResult.Value(credentials)
        }
        override suspend fun replace(scope: StorageScope, credentials: StoredCredentials): PortResult<Unit> = error("No credential writes")
        override suspend fun erase(scope: StorageScope): PortResult<Unit> = error("No credential erasure")
    }

    private fun guestCall(body: String = GUEST_REQUEST_BODY) = ApiCall("createGuestSession",
        body = PrivateBytes(body.encodeToByteArray()), idempotencyKey = SecretText(UUID))

    private fun problemBody(status: String = "503") =
        """{"type":"https://example.test/problems/unavailable","title":"Unavailable","status":$status,"code":"UNAVAILABLE","traceId":"$UUID"}"""

    private companion object {
        const val UUID = "00000000-0000-4000-8000-000000000001"
        const val HEALTH_BODY = """{"status":"healthy","serverTime":"2026-09-13T00:00:00Z","minimumAppVersion":"1.0.0"}"""
        const val GUEST_REQUEST_BODY = """{"installationNonce":"0123456789abcdef0123456789abcdef"}"""
        const val GUEST_SESSION_BODY = """{"guestSessionId":"00000000-0000-4000-8000-000000000002","guestToken":"synthetic-guest-token","expiresAt":"2026-09-14T00:00:00Z","capabilities":["cooking"],"dailyPlanLimit":1}"""
    }
}
