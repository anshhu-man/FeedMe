package com.feedme.server.http

import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.guest.GuestSessionFailure
import com.feedme.server.guest.GuestSessionFailureCode
import com.feedme.server.guest.GuestSessionReply
import com.feedme.server.kitchen.KitchenFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

internal suspend fun ApplicationCall.guestBootstrapOperation(configuration: GuestHttpConfiguration,
    validator: ContractBodyValidator) = guestBoundary("createGuestSession", validator) {
    val input = GuestBootstrapHttpInput.parse(request.headers, request.queryParameters)
    val body = input.readBody(receiveChannel(), validator)
    val reply = try {
        currentCoroutineContext().ensureActive()
        runInterruptible(configuration.databaseDispatcher) { configuration.sessions.create(input.key, body) }
    } finally { body.fill(0) }
    currentCoroutineContext().ensureActive()
    // Success and exact-original replay both use the contract's 201. Never cache the token
    // in StoredReply, logs or ordinary command/outbox storage. No new key on unknown commit.
    val text = validateGuestBootstrapReply(reply, validator)
    respondText(text, ContentType.Application.Json, HttpStatusCode.Created)
}

internal suspend fun ApplicationCall.guestCurrentSessionOperation(configuration: GuestHttpConfiguration,
    validator: ContractBodyValidator) = guestBoundary("getCurrentGuestSession", validator) {
    val input = GuestCurrentSessionHttpInput.parse(request.headers, request.queryParameters)
    input.requireEmptyBody(receiveChannel())
    currentCoroutineContext().ensureActive()
    val reply = runInterruptible(configuration.databaseDispatcher) {
        input.withToken(configuration.sessions::currentSession)
    }
    currentCoroutineContext().ensureActive()
    val bytes = reply.encodeForResponse()
    val text = try {
        if (bytes.size !in 1..8192 || validator.validateResponse("getCurrentGuestSession", 200, bytes, "application/json") != BodyValidationResult.Valid)
            throw GuestHttpFailure(503, "STORAGE_UNAVAILABLE")
        bytes.decodeToString(throwOnInvalidSequence = true)
    } finally { bytes.fill(0) }
    respondText(text, ContentType.Application.Json, HttpStatusCode.OK)
}

/** Header presence selects only a requested credential profile, never an authenticated
 * kind. Strict parsing precedes dispatch. Each profile has exactly one mandatory verifier;
 * any denial/exception is final, with no token-spelling classification or alternate attempt.
 * Account handlers remain unchanged and reparse their original input before authentication.
 */
internal suspend fun ApplicationCall.guestIngredientOperation(configuration: GuestHttpConfiguration,
    validator: ContractBodyValidator, pendingPreferences: PendingPreferencesHttpConfiguration?,
    accountPreferences: AccountPreferencesHttpConfiguration?, kitchen: KitchenHttpConfiguration?) =
    guestBoundary("searchIngredients", validator) {
        rejectGuestSearchUpgrade(request.headers)
        val input = KitchenHttpInput.parse("searchIngredients", request.headers, request.queryParameters, parameters)
        if (input.bearer.deviceSessionId != null) {
            when {
                pendingPreferences != null -> pendingPreferencesOperation("searchIngredients", pendingPreferences, validator)
                accountPreferences != null -> accountPreferencesOperation("searchIngredients", accountPreferences, validator)
                kitchen != null -> kitchenOperation("searchIngredients", kitchen, validator)
                else -> throw GuestHttpFailure(503, "NOT_CONFIGURED")
            }
        } else {
            input.readBody(receiveChannel(), validator) // GET has no body; still verify actual bytes.
            currentCoroutineContext().ensureActive()
            val reply = runInterruptible(configuration.databaseDispatcher) {
                input.bearer.token.use { token -> input.ingredientIds?.let { configuration.search.lookup(token, it) }
                    ?: configuration.search.search(token, input.query, input.cursor, input.limit) }
            }
            currentCoroutineContext().ensureActive()
            val text = checkNotNull(validateKitchenReply("searchIngredients", reply, validator, configuration.search.maxResponseBytes))
            respondText(text, ContentType.Application.Json, HttpStatusCode.OK)
        }
    }

internal fun validateGuestBootstrapReply(reply: GuestSessionReply, validator: ContractBodyValidator): String {
    val bytes = reply.encodeForResponse()
    return try {
        if (bytes.size !in 1..8192 || validator.validateResponse("createGuestSession", 201, bytes, "application/json") != BodyValidationResult.Valid)
            throw GuestHttpFailure(503, "OUTCOME_UNKNOWN")
        bytes.decodeToString(throwOnInvalidSequence = true)
    } finally {
        bytes.fill(0)
        // The HTTP engine owns the immutable response String; scratch clearing does not
        // promise erasure of JVM strings or mutate a response still being transmitted.
    }
}

internal suspend fun ApplicationCall.guestBoundary(operation: String, validator: ContractBodyValidator,
    action: suspend ApplicationCall.() -> Unit) {
    try {
        currentCoroutineContext().ensureActive()
        action()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: GuestHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Guest request unavailable", operationId = operation)
    } catch (failure: GuestSessionFailure) {
        currentCoroutineContext().ensureActive()
        val mapped = guestFailureHttp(operation, failure.code)
        problem(validator, HttpStatusCode.fromValue(mapped.status), mapped.code, "Guest request unavailable", operationId = operation)
    } catch (failure: KitchenHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Ingredient request unavailable", operationId = operation,
            retryAfterSeconds = failure.retryAfterSeconds)
    } catch (failure: KitchenFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Ingredient request unavailable", operationId = operation)
    } catch (_: CommitOutcomeUnknown) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN", "Guest outcome requires reconciliation", operationId = operation)
    }
}

internal fun guestFailureHttp(operation: String, code: GuestSessionFailureCode): GuestHttpFailure {
    require(operation in setOf("createGuestSession", "searchIngredients", "getCurrentGuestSession") + guestKitchenHttpOperations)
    return when (code) {
        GuestSessionFailureCode.INPUT_INVALID -> GuestHttpFailure(422, "INPUT_INVALID")
        GuestSessionFailureCode.ORIGINAL_MISMATCH -> GuestHttpFailure(409, "IDEMPOTENCY_MISMATCH")
        GuestSessionFailureCode.UNAUTHENTICATED -> GuestHttpFailure(401, "UNAUTHENTICATED")
        GuestSessionFailureCode.EXPIRED -> GuestHttpFailure(if (operation == "createGuestSession") 410 else 401, "GUEST_SESSION_EXPIRED")
        GuestSessionFailureCode.POLICY_BLOCKED -> GuestHttpFailure(403, "POLICY_BLOCKED")
        GuestSessionFailureCode.LIMIT_REACHED -> GuestHttpFailure(429, "RATE_LIMITED")
        GuestSessionFailureCode.NOT_CONFIGURED -> GuestHttpFailure(503, "NOT_CONFIGURED")
        GuestSessionFailureCode.STORAGE_UNAVAILABLE -> GuestHttpFailure(503, "STORAGE_UNAVAILABLE")
    }
}

internal fun rejectGuestSearchUpgrade(headers: Headers) {
    fun header(name: String) = headers.getAll(name)?.let { values ->
        if (values.size != 1 || values.single().any(Char::isISOControl)) throw GuestHttpFailure(400, "INVALID_REQUEST")
        values.single()
    }
    if (header(HttpHeaders.Upgrade) != null || header("HTTP2-Settings") != null ||
        header(HttpHeaders.Connection)?.split(',')?.any { it.trim().equals("upgrade", ignoreCase = true) } == true)
        throw GuestHttpFailure(400, "INVALID_REQUEST")
}

internal class GuestHttpFailure(val status: Int, val code: String) : RuntimeException("Guest HTTP request unavailable")
