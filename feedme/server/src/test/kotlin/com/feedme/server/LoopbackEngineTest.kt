package com.feedme.server

import com.feedme.server.config.LocalServerConfig
import com.feedme.server.http.feedMeLocalService
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoopbackEngineTest {
    @Test fun realCioListenerServesHealthAndDenyResponsesThenStops() = runBlocking {
        val config = LocalServerConfig.fromEnvironment(emptyMap())
        // Port 0 is test-only ephemeral allocation, not an accepted deployment configuration.
        val server = embeddedServer(CIO, port = 0, host = config.host) { feedMeLocalService(config) }
        server.start(wait = false)
        try {
            val connector = server.engine.resolvedConnectors().single()
            assertEquals("127.0.0.1", connector.host)
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            for ((path, expected) in listOf("/v1/health" to 200, "/v1/config" to 503, "/v1/admin/health" to 503, "/unknown" to 404)) {
                val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${connector.port}$path"))
                    .timeout(Duration.ofSeconds(5)).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                assertEquals(expected, response.statusCode())
                assertEquals(if (expected == 200) "application/json" else "application/problem+json",
                    response.headers().firstValue("Content-Type").orElse("").substringBefore(';'))
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
                assertNull(response.headers().firstValue("Set-Cookie").orElse(null))
                val body = Json.parseToJsonElement(response.body()).jsonObject
                if (expected == 200) assertEquals("degraded", body.getValue("status").jsonPrimitive.content)
                else assertEquals(response.headers().firstValue("X-Trace-Id").orElseThrow(), body.getValue("traceId").jsonPrimitive.content)
            }
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 1000)
        }
    }
}
