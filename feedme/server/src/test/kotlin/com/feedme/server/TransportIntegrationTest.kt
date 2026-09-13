package com.feedme.server

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireField
import com.feedme.core.ports.ApiCall
import com.feedme.core.ports.ApiReply
import com.feedme.core.ports.Connectivity
import com.feedme.core.ports.ConnectivityPort
import com.feedme.core.ports.EpochClock
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes
import com.feedme.core.ports.SecretText
import com.feedme.core.ports.SecureCredentialStore
import com.feedme.core.ports.SessionBoundary
import com.feedme.core.ports.StorageScope
import com.feedme.core.ports.StoredCredentials
import com.feedme.server.config.LocalServerConfig
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.contract.ContractCatalog
import com.feedme.server.http.feedMeLocalService
import com.feedme.transport.ApiEndpoint
import com.feedme.transport.FeedMeTransport
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Local HTTP protocol integration; the standalone service still implements no product mutations. */
class TransportIntegrationTest {
    @Test fun publicTransportReadsExactSchemaValidDegradedHealthFromStandaloneServer() = withLocalTransport { transport ->
        val reply = assertIs<PortResult.Value<ApiReply>>(
            transport.executePublic(ApiCall("getServiceHealth")),
        ).value

        assertEquals(200, reply.status)
        assertEquals("application/json", assertNotNull(reply.contentType).substringBefore(';'))
        UUID.fromString(assertNotNull(reply.traceId))
        assertNull(reply.retryAfterSeconds)
        val bytes = assertNotNull(reply.body).copyForCodec()
        val expected = """{"status":"degraded","serverTime":"2026-09-13T05:00:00Z","minimumAppVersion":"0.1.0-dev"}"""
        assertContentEquals(expected.encodeToByteArray(), bytes)
        assertEquals(BodyValidationResult.Valid,
            validator.validateResponse("getServiceHealth", reply.status, bytes, reply.contentType))

        val document = WireDocument.decode(bytes)
        assertContentEquals(bytes, document.encodeUtf8())
        assertEquals("degraded", document.requiredField("status").stringOrNull())
        assertEquals("2026-09-13T05:00:00Z", document.requiredField("serverTime").stringOrNull())
        assertEquals("0.1.0-dev", document.requiredField("minimumAppVersion").stringOrNull())
        assertEquals(WireField.Missing, document.field("flagsRevision"))
    }

    @Test fun publicGuestRequestPreservesStandalone503ProblemAsAnHttpReply() = withLocalTransport { transport ->
        val requestBytes = """{"installationNonce":"synthetic-loopback-installation-0001","locale":"en-IN"}""".encodeToByteArray()
        val requestDocument = WireDocument.decode(requestBytes)
        assertContentEquals(requestBytes, requestDocument.encodeUtf8())
        assertEquals(BodyValidationResult.Valid,
            validator.validateRequest("createGuestSession", requestBytes, "application/json"))
        val reply = assertIs<PortResult.Value<ApiReply>>(
            transport.executePublic(ApiCall(
                operationId = "createGuestSession",
                body = PrivateBytes(requestDocument.encodeUtf8()),
                idempotencyKey = SecretText("00000000-0000-4000-8000-000000000001"),
            )),
        ).value

        assertEquals(503, reply.status)
        assertEquals("application/problem+json", assertNotNull(reply.contentType).substringBefore(';'))
        val trace = assertNotNull(reply.traceId)
        UUID.fromString(trace)
        assertNull(reply.retryAfterSeconds)
        val bytes = assertNotNull(reply.body).copyForCodec()
        val expected = """{"type":"about:blank","title":"Operation unavailable","status":503,"code":"OPERATION_NOT_IMPLEMENTED","traceId":"$trace","detail":"This local development service does not implement this operation."}"""
        assertContentEquals(expected.encodeToByteArray(), bytes)
        assertEquals(BodyValidationResult.Valid,
            validator.validateResponse("createGuestSession", reply.status, bytes, reply.contentType))

        val problem = WireDocument.decode(bytes)
        assertContentEquals(bytes, problem.encodeUtf8())
        assertEquals("about:blank", problem.requiredField("type").stringOrNull())
        assertEquals("503", problem.requiredField("status").numberTokenOrNull())
        assertEquals("OPERATION_NOT_IMPLEMENTED", problem.requiredField("code").stringOrNull())
        assertEquals(trace, problem.requiredField("traceId").stringOrNull())
        assertEquals("This local development service does not implement this operation.",
            problem.requiredField("detail").stringOrNull())
        assertEquals(WireField.Missing, problem.field("retryAfterSeconds"))
        assertEquals(WireField.Missing, problem.field("currentVersion"))
    }

    private fun withLocalTransport(block: suspend (FeedMeTransport) -> Unit) {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "feedme-transport-integration-owner").apply { isDaemon = true }
        }.asCoroutineDispatcher().use { ownerDispatcher ->
            runBlocking(ownerDispatcher) {
                val config = LocalServerConfig.fromEnvironment(emptyMap())
                val fixedClock = Clock.fixed(Instant.parse("2026-09-13T05:00:00Z"), ZoneOffset.UTC)
                // Same application and CIO engine as Main; only the port and clock are test fixtures.
                val server = embeddedServer(CIO, port = 0, host = config.host) {
                    feedMeLocalService(config, catalog, fixedClock, validator)
                }
                val credentials = UnusedCredentialStore()
                var transport: FeedMeTransport? = null
                try {
                    server.start(wait = false)
                    val connector = server.engine.resolvedConnectors().single()
                    assertEquals("127.0.0.1", connector.host)
                    val activeTransport = FeedMeTransport.create(
                        endpoint = ApiEndpoint.loopback("standalone-integration-test", connector.port),
                        credentials = credentials,
                        boundary = SessionBoundary(),
                        ownerDispatcher = ownerDispatcher,
                        clock = EpochClock { fixedClock.millis() },
                        connectivity = ConnectivityPort { Connectivity.ONLINE },
                    )
                    transport = activeTransport
                    block(activeTransport)
                    assertEquals(0, credentials.calls.get(), "Public calls must not access secure storage")
                } finally {
                    try {
                        transport?.close()
                    } finally {
                        server.stop(gracePeriodMillis = 100, timeoutMillis = 1000)
                    }
                }
            }
        }
    }

    private fun WireDocument.requiredField(name: String): WireDocument =
        assertIs<WireField.Value<WireDocument>>(field(name)).value

    private class UnusedCredentialStore : SecureCredentialStore {
        val calls = AtomicInteger()

        override suspend fun read(scope: StorageScope): PortResult<StoredCredentials?> = unexpectedCall()
        override suspend fun replace(scope: StorageScope, credentials: StoredCredentials): PortResult<Unit> = unexpectedCall()
        override suspend fun erase(scope: StorageScope): PortResult<Unit> = unexpectedCall()

        private fun unexpectedCall(): Nothing {
            calls.incrementAndGet()
            error("Public protocol integration must not access secure storage")
        }
    }

    private companion object {
        val catalog by lazy { ContractCatalog.bundled() }
        val validator by lazy { ContractBodyValidator.bundled(catalog) }
    }
}
