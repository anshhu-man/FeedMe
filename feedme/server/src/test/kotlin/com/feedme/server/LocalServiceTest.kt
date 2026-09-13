package com.feedme.server

import com.feedme.server.config.LocalServerConfig
import com.feedme.server.contract.ContractCatalog
import com.feedme.server.http.feedMeLocalService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalServiceTest {
    private val config = LocalServerConfig.fromEnvironment(emptyMap())
    private val instant = Instant.parse("2026-09-13T05:00:00Z")
    private val clock = Clock.fixed(instant, ZoneOffset.UTC)

    @Test fun healthIsPublicAndHonestlyDegraded() = testApplication {
        application { feedMeLocalService(config, clock = clock) }
        val response = client.get("/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertResponseHeaders(response)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(setOf("status", "serverTime", "minimumAppVersion"), body.keys)
        assertEquals("degraded", body.getValue("status").jsonPrimitive.content)
        assertEquals(instant, Instant.parse(body.getValue("serverTime").jsonPrimitive.content))
        assertEquals("0.1.0-dev", body.getValue("minimumAppVersion").jsonPrimitive.content)
    }

    @Test fun everyUnimplementedCanonicalRouteReturns503WithoutSuccessfulMutation() = testApplication {
        val catalog = ContractCatalog.bundled()
        application { feedMeLocalService(config, catalog, clock) }
        var denied = 0
        for (operation in catalog.operations.filter { it.id != "getServiceHealth" }) {
            val concretePath = operation.path.replace(Regex("\\{[^}]+}"), "11111111-1111-4111-8111-111111111111")
            val response = client.request(concretePath) {
                method = HttpMethod.parse(operation.method)
                header(HttpHeaders.Authorization, "Bearer fake-sensitive-token")
                header("X-Device-Session", "11111111-1111-4111-8111-111111111111")
                header("Idempotency-Key", "11111111-1111-4111-8111-111111111111")
                header(HttpHeaders.ContentType, "application/json")
                if (operation.method != "GET") setBody("{\"untrusted\":\"sensitive-body\"}")
            }
            assertProblem(response, HttpStatusCode.ServiceUnavailable, "OPERATION_NOT_IMPLEMENTED")
            assertNull(response.headers[HttpHeaders.RetryAfter])
            denied++
        }
        assertEquals(200, denied)
    }

    @Test fun configurationAndStaffHealthDenyEvenWithNoCredentials() = testApplication {
        application { feedMeLocalService(config, clock = clock) }
        for (path in listOf("/v1/config", "/v1/admin/health")) {
            assertProblem(client.get(path), HttpStatusCode.ServiceUnavailable, "OPERATION_NOT_IMPLEMENTED")
        }
    }

    @Test fun unknownPathsAndMethodsDoNotExposeRequestDetails() = testApplication {
        application { feedMeLocalService(config, clock = clock) }
        assertProblem(client.get("/v1/not-real/secret-owner?token=sensitive-query"), HttpStatusCode.NotFound, "ROUTE_NOT_FOUND")
        assertProblem(client.request("/v1/health") { method = HttpMethod.Post }, HttpStatusCode.NotFound, "ROUTE_NOT_FOUND")
        assertProblem(client.request("/v1/health") { method = HttpMethod.Options }, HttpStatusCode.NotFound, "ROUTE_NOT_FOUND")
    }

    @Test fun tracesAreServerGeneratedUniqueAndNotReflected() = testApplication {
        application { feedMeLocalService(config, clock = clock) }
        val first = client.get("/v1/config") { header("X-Trace-Id", "sensitive-injected-trace") }
        val second = client.get("/v1/config")
        assertProblem(first, HttpStatusCode.ServiceUnavailable, "OPERATION_NOT_IMPLEMENTED")
        assertNotEquals(first.headers["X-Trace-Id"], second.headers["X-Trace-Id"])
        assertFalse(first.bodyAsText().contains("sensitive-injected-trace"))
    }

    @Test fun unexpectedHandlerErrorsAreRedacted() = testApplication {
        application {
            feedMeLocalService(config, clock = clock)
            routing { get("/test-only/failure") { error("sensitive-internal-database-detail") } }
        }
        assertProblem(client.get("/test-only/failure"), HttpStatusCode.InternalServerError, "INTERNAL_ERROR")
    }

    @Test fun malformedHandlerInputsUseCanonicalProblem() = testApplication {
        application {
            feedMeLocalService(config, clock = clock)
            routing { get("/test-only/bad-input") { throw io.ktor.server.plugins.BadRequestException("sensitive-input") } }
        }
        assertProblem(client.get("/test-only/bad-input"), HttpStatusCode.BadRequest, "INVALID_REQUEST")
    }

    @Test fun invalidGeneratedHealthFallsBackToRedactedProblem() = testApplication {
        val outOfRfc3339Range = Clock.fixed(Instant.parse("+10000-01-01T00:00:00Z"), ZoneOffset.UTC)
        application { feedMeLocalService(config, clock = outOfRfc3339Range) }
        val response = client.get("/v1/health")
        assertProblem(response, HttpStatusCode.InternalServerError, "INTERNAL_ERROR")
        assertFalse(response.bodyAsText().contains("10000"))
    }

    private fun assertResponseHeaders(response: HttpResponse, mediaType: String = "application/json") {
        UUID.fromString(assertNotNull(response.headers["X-Trace-Id"]))
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals(mediaType, response.headers[HttpHeaders.ContentType].orEmpty().substringBefore(';'))
        assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertNull(response.headers[HttpHeaders.SetCookie])
    }

    private suspend fun assertProblem(response: HttpResponse, status: HttpStatusCode, code: String) {
        assertEquals(status, response.status)
        assertResponseHeaders(response, "application/problem+json")
        val text = response.bodyAsText()
        val body = Json.parseToJsonElement(text).jsonObject
        assertTrue(setOf("type", "title", "status", "code", "traceId").all { it in body })
        assertTrue(body.keys.all { it in setOf("type", "title", "status", "code", "traceId", "detail") })
        assertTrue(URI(body.getValue("type").jsonPrimitive.content).isAbsolute)
        assertEquals(status.value, body.getValue("status").jsonPrimitive.int)
        assertEquals(code, body.getValue("code").jsonPrimitive.content)
        assertEquals(response.headers["X-Trace-Id"], body.getValue("traceId").jsonPrimitive.content)
        assertFalse(text.contains("sensitive"))
        assertFalse(text.contains("secret-owner"))
        assertFalse(text.contains("Exception"))
    }
}
