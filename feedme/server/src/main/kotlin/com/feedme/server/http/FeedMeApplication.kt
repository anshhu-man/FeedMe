package com.feedme.server.http

import com.feedme.server.config.LocalServerConfig
import com.feedme.server.contract.ContractCatalog
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.contract.BodyValidationResult
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import java.time.Clock
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val traceKey = AttributeKey<String>("FeedMeTraceId")
private val localBodyValidator by lazy { ContractBodyValidator.bundled() }
private val problemContentType = ContentType.parse("application/problem+json")
private val responseContext = createApplicationPlugin("FeedMeResponseContext") {
    onCall { call ->
        // Ignore incoming trace headers: untrusted identifiers must not enter responses/logs.
        val trace = UUID.randomUUID().toString()
        call.attributes.put(traceKey, trace)
        call.response.headers.append("X-Trace-Id", trace)
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.response.headers.append("X-Content-Type-Options", "nosniff")
    }
}

/** The only implemented operation is public health. All other product routes deny with 503. */
fun Application.feedMeLocalService(
    config: LocalServerConfig,
    catalog: ContractCatalog = ContractCatalog.bundled(),
    clock: Clock = Clock.systemUTC(),
    bodyValidator: ContractBodyValidator = localBodyValidator,
) {
    install(responseContext)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            when (cause) {
                is CancellationException -> throw cause
                is BadRequestException -> call.problem(bodyValidator, HttpStatusCode.BadRequest, "INVALID_REQUEST", "Invalid request")
                else -> call.problem(bodyValidator, HttpStatusCode.InternalServerError, "INTERNAL_ERROR", "Internal server error")
            }
            // No raw exception, URI, body, authorization header or principal logging here.
        }
        status(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed) { call, _ ->
            call.problem(bodyValidator, HttpStatusCode.NotFound, "ROUTE_NOT_FOUND", "Route not found")
        }
    }
    routing {
        catalog.operations.forEach { operation ->
            route(operation.path, HttpMethod.parse(operation.method)) {
                handle {
                    if (operation.id == "getServiceHealth") {
                        val health = buildJsonObject {
                            put("status", "degraded")
                            put("serverTime", clock.instant().toString())
                            put("minimumAppVersion", config.minimumAppVersion)
                        }
                        val text = health.toString()
                        check(bodyValidator.validateResponse(operation.id, 200, text.encodeToByteArray(), "application/json") == BodyValidationResult.Valid) {
                            "Local response violates the bundled contract"
                        }
                        call.respondText(text, ContentType.Application.Json, HttpStatusCode.OK)
                    } else {
                        // Do not accept fake tokens, grant access, parse/mutate data or acknowledge webhooks.
                        // No Retry-After promise: this is missing implementation, not transient capacity.
                        call.problem(bodyValidator, HttpStatusCode.ServiceUnavailable, "OPERATION_NOT_IMPLEMENTED", "Operation unavailable",
                            "This local development service does not implement this operation.", operation.id)
                    }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.problem(
    validator: ContractBodyValidator,
    status: HttpStatusCode,
    code: String,
    title: String,
    detail: String? = null,
    operationId: String? = null,
) {
    val body = buildJsonObject {
        put("type", "about:blank")
        put("title", title)
        put("status", status.value)
        put("code", code)
        put("traceId", attributes[traceKey])
        detail?.let { put("detail", it) }
    }
    val text = body.toString()
    val bytes = text.encodeToByteArray()
    val result = if (operationId == null) validator.validateSchema("Problem", bytes)
        else validator.validateResponse(operationId, status.value, bytes, "application/problem+json")
    check(result == BodyValidationResult.Valid) { "Local Problem violates the bundled contract" }
    respondText(text, problemContentType, status)
}
