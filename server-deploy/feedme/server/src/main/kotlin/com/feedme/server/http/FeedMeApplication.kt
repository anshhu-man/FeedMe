package com.feedme.server.http

import com.feedme.server.config.LocalServerConfig
import com.feedme.server.contract.ContractCatalog
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.runtime.ServiceHealthMode
import com.feedme.server.runtime.ServiceLifecycle
import com.feedme.server.runtime.ServiceLifecycleState
import com.feedme.server.runtime.ServiceRequestAdmission
import com.feedme.server.runtime.AccountCoreDependencyHealth
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val traceKey = AttributeKey<String>("FeedMeTraceId")
private val authoredProblemKey = AttributeKey<Unit>("FeedMeAuthoredProblem")
private val admissionPermitKey = AttributeKey<ServiceRequestAdmission.Permit>("FeedMeRequestPermit")
private val localBodyValidator by lazy { ContractBodyValidator.bundled() }
private val problemContentType = ContentType.parse("application/problem+json")
private val responseContext = createApplicationPlugin("FeedMeResponseContext") {
    onCall { call ->
        // Ignore incoming trace headers: untrusted identifiers must not enter responses/logs.
        val trace = call.httpObservationTraceId()
        call.attributes.put(traceKey, trace)
        call.response.headers.append("X-Trace-Id", trace)
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.response.headers.append("X-Content-Type-Options", "nosniff")
    }
}

/** Default is health-only/503. Product route groups require explicit trusted composition. */
fun Application.feedMeLocalService(
    config: LocalServerConfig,
    catalog: ContractCatalog = ContractCatalog.bundled(),
    clock: Clock = Clock.systemUTC(),
    bodyValidator: ContractBodyValidator = localBodyValidator,
    planning: PlanningHttpConfiguration? = null,
    social: SocialHttpConfiguration? = null,
    kitchen: KitchenHttpConfiguration? = null,
    cooking: CookingHttpConfiguration? = null,
    savedRecipe: SavedRecipeHttpConfiguration? = null,
    media: MediaHttpConfiguration? = null,
    postDraft: PostDraftHttpConfiguration? = null,
    postPublication: PostPublicationHttpConfiguration? = null,
    account: AccountHttpConfiguration? = null,
    pendingPreferences: PendingPreferencesHttpConfiguration? = null,
    accountPreferences: AccountPreferencesHttpConfiguration? = null,
    pantry: PantryHttpConfiguration? = null,
    accountPantry: AccountPantryHttpConfiguration? = null,
    lifecycle: ServiceLifecycle = ServiceLifecycle(),
    healthMode: ServiceHealthMode = ServiceHealthMode.LOCAL_LIVENESS,
    observationSink: HttpObservationSink = HttpObservationSink { },
    guest: GuestHttpConfiguration? = null,
    requestAdmission: ServiceRequestAdmission = ServiceRequestAdmission(config.maximumInFlightRequests),
    accountPlanning: AccountPlanningHttpConfiguration? = null,
    accountCooking: AccountCookingHttpConfiguration? = null,
    accountSaved: AccountSavedRecipeHttpConfiguration? = null,
    accountCoreHealth: AccountCoreDependencyHealth? = null,
) {
    require((healthMode == ServiceHealthMode.ACCOUNT_CORE_DEPENDENCIES) == (accountCoreHealth != null)) {
        "Configured core health requires its owned dependency checker"
    }
    require(planning == null || accountPlanning == null) { "Choose one planning route authority" }
    require(cooking == null || accountCooking == null) { "Choose one cooking route authority" }
    require(savedRecipe == null || accountSaved == null) { "Choose one saved route authority" }
    require(listOf(kitchen, pendingPreferences, accountPreferences).count { it != null } <= 1) {
        "Choose at most one kitchen/self-preferences route authority"
    }
    require(listOf(kitchen, pantry, accountPantry).count { it != null } <= 1) {
        "Choose at most one kitchen/pantry route authority"
    }
    // The lifecycle only removes admission; it never establishes product readiness or
    // replaces account, object, catalog or transactional authorization.
    monitor.subscribe(ApplicationStarted) { lifecycle.startServing() }
    monitor.subscribe(ApplicationStopPreparing) { lifecycle.beginDraining() }
    monitor.subscribe(ApplicationStopped) { lifecycle.markStopped() }
    installHttpObservability(catalog, observationSink)
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
            // Only an empty routing fallback needs replacement. Keep an explicitly authored,
            // canonical operation Problem (e.g. private PLAN_UNAVAILABLE) unchanged.
            if (!call.attributes.contains(authoredProblemKey))
                call.problem(bodyValidator, HttpStatusCode.NotFound, "ROUTE_NOT_FOUND", "Route not found")
        }
    }
    // Ktor's CallFailed/StatusPages hooks insert a phase BEFORE Setup. Add the lifetime
    // wrapper after installing those plugins, before the actual first phase, so a handled
    // error retains its permit through its suspended/error response as well. A route-level
    // finally or ordinary Setup interceptor would release too early on that path.
    val admissionLifetime = PipelinePhase("FeedMeRequestLifetime")
    insertPhaseBefore(items.first(), admissionLifetime)
    intercept(admissionLifetime) {
        try { proceed() }
        finally { call.attributes.getOrNull(admissionPermitKey)?.close() }
    }
    routing {
        catalog.operations.forEach { operation ->
            route(operation.path, HttpMethod.parse(operation.method)) {
                handle {
                    call.markHttpOperation(operation.id)
                    // Admission linearizes here. Requests already admitted before draining
                    // may finish normally; a later refusal never promises mutation rollback.
                    if (!lifecycle.tryAdmit()) {
                        val draining = lifecycle.state in setOf(ServiceLifecycleState.DRAINING, ServiceLifecycleState.STOPPED)
                        call.problem(bodyValidator, HttpStatusCode.ServiceUnavailable,
                            if (draining) "SERVICE_DRAINING" else "SERVICE_NOT_READY", "Service unavailable",
                            operationId = operation.id)
                        return@handle
                    }
                    currentCoroutineContext().ensureActive()
                    if (operation.id != "getServiceHealth") {
                        val permit = requestAdmission.tryAcquire()
                        if (permit == null) {
                            call.problem(bodyValidator, HttpStatusCode.ServiceUnavailable, "SERVICE_BUSY", "Service unavailable",
                                operationId = operation.id)
                            return@handle
                        }
                        call.attributes.put(admissionPermitKey, permit)
                    }
                    if (operation.id == "getServiceHealth" &&
                        (healthMode == ServiceHealthMode.UNCONFIGURED_READINESS ||
                            healthMode == ServiceHealthMode.ACCOUNT_CORE_DEPENDENCIES && accountCoreHealth?.available() != true)) {
                        call.problem(bodyValidator, HttpStatusCode.ServiceUnavailable, "SERVICE_NOT_READY", "Service unavailable",
                            operationId = operation.id)
                    } else if (operation.id == "getServiceHealth") {
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
                    } else if (guest != null && operation.id == "createGuestSession") {
                        call.guestBootstrapOperation(guest, bodyValidator)
                    } else if (guest != null && operation.id == "getCurrentGuestSession") {
                        call.guestCurrentSessionOperation(guest, bodyValidator)
                    } else if (account != null && operation.id in accountHttpOperations) {
                        call.accountOperation(operation.id, account, bodyValidator)
                    } else if (planning != null && operation.id in planningHttpOperations) {
                        call.planningOperation(operation.id, planning, bodyValidator)
                    } else if (accountPlanning != null && operation.id in planningHttpOperations) {
                        call.accountPlanningOperation(operation.id, accountPlanning, bodyValidator)
                    } else if (social != null && operation.id in socialHttpOperations) {
                        call.socialOperation(operation.id, social, bodyValidator)
                    } else if (guest != null && operation.id == "searchIngredients") {
                        call.guestIngredientOperation(guest, bodyValidator, pendingPreferences, accountPreferences, kitchen)
                    } else if (guest != null && operation.id in guestKitchenHttpOperations) {
                        call.guestKitchenOperation(operation.id, guest, bodyValidator, pendingPreferences,
                            accountPreferences, kitchen, pantry, accountPantry)
                    } else if (pendingPreferences != null && operation.id in pendingPreferencesHttpOperations) {
                        call.pendingPreferencesOperation(operation.id, pendingPreferences, bodyValidator)
                    } else if (accountPreferences != null && operation.id in accountPreferencesHttpOperations) {
                        call.accountPreferencesOperation(operation.id, accountPreferences, bodyValidator)
                    } else if (kitchen != null && operation.id in kitchenHttpOperations) {
                        call.kitchenOperation(operation.id, kitchen, bodyValidator)
                    } else if (pantry != null && operation.id in pantryHttpOperations) {
                        call.pantryOperation(operation.id, pantry, bodyValidator)
                    } else if (accountPantry != null && operation.id in pantryHttpOperations) {
                        call.accountPantryOperation(operation.id, accountPantry, bodyValidator)
                    } else if (cooking != null && operation.id in cookingHttpOperations) {
                        call.cookingOperation(operation.id, cooking, bodyValidator)
                    } else if (accountCooking != null && operation.id in cookingHttpOperations) {
                        call.accountCookingOperation(operation.id, accountCooking, bodyValidator)
                    } else if (savedRecipe != null && operation.id in savedRecipeHttpOperations) {
                        call.savedRecipeOperation(operation.id, savedRecipe, bodyValidator)
                    } else if (accountSaved != null && operation.id in savedRecipeHttpOperations) {
                        call.accountSavedRecipeOperation(operation.id, accountSaved, bodyValidator)
                    } else if (media != null && operation.id in mediaHttpOperations) {
                        call.mediaOperation(operation.id, media, bodyValidator)
                    } else if (postDraft != null && operation.id in postDraftHttpOperations) {
                        call.postDraftOperation(operation.id, postDraft, bodyValidator)
                    } else if (postPublication != null && operation.id == "publishPost") {
                        call.postPublicationOperation(postPublication, bodyValidator)
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

internal suspend fun ApplicationCall.problem(
    validator: ContractBodyValidator,
    status: HttpStatusCode,
    code: String,
    title: String,
    detail: String? = null,
    operationId: String? = null,
    retryAfterSeconds: Long? = null,
) {
    require(retryAfterSeconds == null || (retryAfterSeconds >= 0 && status.value in setOf(429, 503)))
    val body = buildJsonObject {
        put("type", "about:blank")
        put("title", title)
        put("status", status.value)
        put("code", code)
        put("traceId", attributes[traceKey])
        detail?.let { put("detail", it) }
        retryAfterSeconds?.let { put("retryAfterSeconds", it) }
    }
    val text = body.toString()
    val bytes = text.encodeToByteArray()
    val result = if (operationId == null) validator.validateSchema("Problem", bytes)
        else validator.validateResponse(operationId, status.value, bytes, "application/problem+json")
    check(result == BodyValidationResult.Valid) { "Local Problem violates the bundled contract" }
    attributes.put(authoredProblemKey, Unit)
    if (code == "OUTCOME_UNKNOWN") markHttpOutcomeUnknown()
    // Problem accepts zero. The shared transport's Retry-After profile accepts positive
    // integer seconds only, so zero remains exact in the body without inventing a delay.
    retryAfterSeconds?.takeIf { it > 0 }?.let { response.headers.append(HttpHeaders.RetryAfter, it.toString()) }
    respondText(text, problemContentType, status)
}
