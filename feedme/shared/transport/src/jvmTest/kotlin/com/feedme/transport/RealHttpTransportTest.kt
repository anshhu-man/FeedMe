package com.feedme.transport

import com.feedme.contracts.WireLimits
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
import com.sun.net.httpserver.HttpExchange as ServerExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual socket requests through the owned JVM engine; no MockEngine or remote services. */
class RealHttpTransportTest {
    @Test fun post503WithImmediateRetryDoesNotReplayTheMutation() = runTest {
        LoopbackServer { exchange, _ ->
            exchange.responseHeaders.add("Retry-After", "0")
            exchange.json(503, PROBLEM_BODY, "application/problem+json")
        }.use { server ->
            withTransport(server) { transport ->
                val result = transport.executePublic(guestCall())

                // Zero is outside the adapter's accepted metadata, independently of engine retries.
                assertEquals(PortResult.Failure(FailureReason.OUTCOME_UNKNOWN), result)
                val request = server.requests.single()
                assertEquals("POST", request.method)
                assertEquals("/v1/guest-sessions", request.path)
                assertEquals(IDEMPOTENCY_KEY, request.header("Idempotency-Key"))
                assertContentEquals(GUEST_REQUEST_BODY.encodeToByteArray(), request.body)
                assertNull(request.header("Authorization"))
            }
        }
    }

    @Test fun bodylessGet503WithImmediateRetryCannotMakeASecondNetworkAttempt() = runTest {
        LoopbackServer { exchange, _ ->
            exchange.responseHeaders.add("Retry-After", "0")
            exchange.json(503, PROBLEM_BODY, "application/problem+json")
        }.use { server ->
            withTransport(server) { transport ->
                assertEquals(
                    PortResult.Failure(FailureReason.UNAVAILABLE),
                    transport.executePublic(ApiCall("getServiceHealth")),
                )
                val request = server.requests.single()
                assertEquals("GET", request.method)
                assertEquals("/v1/health", request.path)
                assertTrue(request.body.isEmpty())
            }
        }
    }

    @Test fun redirectDoesNotReachTheLocationEndpoint() = runTest {
        LoopbackServer { exchange, _ -> exchange.json(200) }.use { destination ->
            LoopbackServer { exchange, _ ->
                exchange.responseHeaders.add("Location", "${destination.origin}/v1/health")
                exchange.sendResponseHeaders(302, -1)
            }.use { origin ->
                withTransport(origin) { transport ->
                    assertEquals(
                        PortResult.Failure(FailureReason.UNAVAILABLE),
                        transport.executePublic(ApiCall("getServiceHealth")),
                    )
                    assertEquals(1, origin.requests.size)
                    assertTrue(destination.requests.isEmpty())
                }
            }
        }
    }

    @Test fun responseCookieIsNotStoredOrSentOnTheNextCall() = runTest {
        LoopbackServer { exchange, _ ->
            exchange.responseHeaders.add("Set-Cookie", "ambient_session=loopback-only; Path=/; HttpOnly")
            exchange.json(200)
        }.use { server ->
            withTransport(server) { transport ->
                repeat(2) {
                    val reply = assertIs<PortResult.Value<ApiReply>>(
                        transport.executePublic(ApiCall("getServiceHealth")),
                    ).value
                    assertEquals(200, reply.status)
                }
                assertEquals(2, server.requests.size)
                server.requests.forEach { request ->
                    assertNull(request.header("Cookie"))
                    assertNull(request.header("Authorization"))
                }
            }
        }
    }

    @Test fun ambientProxySelectorCannotRouteTheOwnedClient() = synchronized(proxySelectorLock) {
        runTest {
            LoopbackServer { exchange, _ -> exchange.json(200) }.use { hostileProxy ->
                LoopbackServer { exchange, _ -> exchange.json(200) }.use { origin ->
                    val selections = AtomicInteger()
                    val previous = ProxySelector.getDefault()
                    val hostileSelector = object : ProxySelector() {
                        override fun select(uri: URI): List<Proxy> {
                            selections.incrementAndGet()
                            return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", hostileProxy.port)))
                        }

                        override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) = Unit
                    }
                    try {
                        ProxySelector.setDefault(hostileSelector)
                        // Construct after replacing the global default, as a newly built client would.
                        withTransport(origin) { transport ->
                            val reply = assertIs<PortResult.Value<ApiReply>>(
                                transport.executePublic(ApiCall("getServiceHealth")),
                            ).value
                            assertEquals(200, reply.status)
                            assertEquals(1, origin.requests.size)
                            assertEquals(0, selections.get())
                            assertTrue(hostileProxy.requests.isEmpty())
                        }
                    } finally {
                        ProxySelector.setDefault(previous)
                    }
                }
            }
        }
    }

    @Test fun disconnectAfterReceivingWriteIsUnknownAndCallerRetryKeepsOriginalKey() = runTest {
        LoopbackServer { exchange, requestNumber ->
            // The fixture has already received the entire body before invoking this handler.
            if (requestNumber == 1) exchange.close() else exchange.json(201, GUEST_SESSION_BODY)
        }.use { server ->
            withTransport(server) { transport ->
                assertEquals(
                    PortResult.Failure(FailureReason.OUTCOME_UNKNOWN),
                    transport.executePublic(guestCall()),
                )
                assertEquals(1, server.requests.size, "An uncertain mutation must not replay itself")

                val retry = assertIs<PortResult.Value<ApiReply>>(transport.executePublic(guestCall())).value
                assertEquals(201, retry.status)
                assertEquals(2, server.requests.size, "Only the explicit caller retry sends again")
                server.requests.forEach { request ->
                    assertEquals("POST", request.method)
                    assertEquals("/v1/guest-sessions", request.path)
                    assertEquals(IDEMPOTENCY_KEY, request.header("Idempotency-Key"))
                    assertContentEquals(GUEST_REQUEST_BODY.encodeToByteArray(), request.body)
                }
            }
        }
    }

    @Test fun chunkedJsonBeyondTheAcceptedByteLimitIsRejected() = runTest {
        LoopbackServer { exchange, _ ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 0) // Chunked; no declared Content-Length to reject early.
            exchange.responseBody.use { output ->
                output.write('"'.code)
                val chunk = ByteArray(8192) { 'x'.code.toByte() }
                var remaining = WireLimits().maxBytes - 1
                while (remaining > 0) {
                    val count = minOf(remaining, chunk.size)
                    output.write(chunk, 0, count)
                    output.flush()
                    remaining -= count
                }
                output.write('"'.code) // A valid JSON string totaling maxBytes + 1 bytes.
            }
        }.use { server ->
            withTransport(server) { transport ->
                assertEquals(
                    PortResult.Failure(FailureReason.UNAVAILABLE),
                    transport.executePublic(ApiCall("getServiceHealth")),
                )
                assertEquals(1, server.requests.size)
            }
        }
        // This checks the accepted document bound, not a bound on native engine/socket memory.
    }

    private suspend fun TestScope.withTransport(server: LoopbackServer, block: suspend (FeedMeTransport) -> Unit) {
        val transport = FeedMeTransport.create(
            endpoint = ApiEndpoint.loopback("jvm-loopback-test", server.port),
            credentials = unusedCredentials,
            boundary = SessionBoundary(),
            ownerDispatcher = StandardTestDispatcher(testScheduler),
            clock = EpochClock { 1_000 },
            connectivity = ConnectivityPort { Connectivity.ONLINE },
        )
        try {
            block(transport)
        } finally {
            transport.close()
        }
    }

    private fun guestCall() = ApiCall(
        operationId = "createGuestSession",
        body = PrivateBytes(GUEST_REQUEST_BODY.encodeToByteArray()),
        idempotencyKey = SecretText(IDEMPOTENCY_KEY),
    )

    private companion object {
        const val IDEMPOTENCY_KEY = "00000000-0000-4000-8000-000000000001"
        val proxySelectorLock = Any()
        val unusedCredentials = object : SecureCredentialStore {
            override suspend fun read(scope: StorageScope): PortResult<StoredCredentials?> =
                error("Public calls must not read credentials")

            override suspend fun replace(scope: StorageScope, credentials: StoredCredentials): PortResult<Unit> =
                error("Transport must not persist credentials")

            override suspend fun erase(scope: StorageScope): PortResult<Unit> =
                error("Transport must not erase credentials")
        }
    }
}

private const val HEALTH_BODY = """{"status":"healthy","serverTime":"2026-09-13T00:00:00Z","minimumAppVersion":"1.0.0"}"""
private const val PROBLEM_BODY = """{"type":"https://example.test/problems/unavailable","title":"Unavailable","status":503,"code":"UNAVAILABLE","traceId":"synthetic-trace"}"""
private const val GUEST_REQUEST_BODY = """{"installationNonce":"0123456789abcdef0123456789abcdef"}"""
private const val GUEST_SESSION_BODY = """{"guestSessionId":"00000000-0000-4000-8000-000000000002","guestToken":"synthetic-guest-token","expiresAt":"2026-09-14T00:00:00Z","capabilities":["cooking"],"dailyPlanLimit":1}"""

private data class ReceivedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]?.joinToString(",")
}

private class LoopbackServer(handler: (ServerExchange, Int) -> Unit) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "feedme-loopback-http-test").apply { isDaemon = true }
    }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val received = ConcurrentLinkedQueue<ReceivedRequest>()
    private val handlerFailures = ConcurrentLinkedQueue<Throwable>()
    private val requestCount = AtomicInteger()
    val port: Int get() = server.address.port
    val origin: String get() = "http://127.0.0.1:$port"
    val requests: List<ReceivedRequest> get() = received.toList()

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                received.add(
                    ReceivedRequest(
                        method = exchange.requestMethod,
                        path = exchange.requestURI.rawPath,
                        headers = exchange.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.toList() },
                        body = exchange.requestBody.use { it.readBytes() },
                    ),
                )
                handler(exchange, requestCount.incrementAndGet())
            } catch (_: IOException) {
                // Early rejection closes the connection while the fixture may still be writing.
            } catch (failure: Throwable) {
                handlerFailures.add(failure)
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    override fun close() {
        try {
            server.stop(0)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Loopback executor did not stop")
        }
        assertTrue(handlerFailures.isEmpty(), "Unexpected fixture failure: ${handlerFailures.firstOrNull()}")
    }
}

private fun ServerExchange.json(status: Int, body: String = HEALTH_BODY, media: String = "application/json") {
    val bytes = body.encodeToByteArray()
    responseHeaders.add("Content-Type", media)
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
