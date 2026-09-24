package com.feedme.server.http

import com.feedme.contracts.CanonicalFormats
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.cooking.CookingFailure
import com.feedme.server.db.CommandResult
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.StoredReply
import com.feedme.server.guest.GuestSessionFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

internal val guestCookingHttpOperations = cookingHttpOperations

/** Select one credential profile before a body is consumed. Header presence only selects
 * the account path; each concrete owner still performs its complete authentication. A
 * missing selected configuration never falls through to the other authority. */
internal suspend fun ApplicationCall.credentialCookingOperation(
    operation: String,
    account: AccountCookingHttpConfiguration?,
    guest: GuestHttpConfiguration?,
    validator: ContractBodyValidator,
) {
    require(operation in cookingHttpOperations)
    val devices = request.headers.getAll("X-Device-Session")
    if (devices != null && (devices.size != 1 || devices.single().any(Char::isISOControl) ||
            !CanonicalFormats.accepts("uuid", devices.single()))) {
        problem(validator, HttpStatusCode.BadRequest, "INVALID_REQUEST",
            "Cooking request unavailable", operationId = operation)
        return
    }
    when {
        devices != null && account != null -> accountCookingOperation(operation, account, validator)
        devices == null && guest?.cooking != null -> guestCookingOperation(operation, guest, validator)
        devices != null -> problem(validator, HttpStatusCode.ServiceUnavailable, "NOT_CONFIGURED",
            "Cooking request unavailable", operationId = operation)
        account != null -> accountCookingOperation(operation, account, validator)
        else -> problem(validator, HttpStatusCode.ServiceUnavailable, "NOT_CONFIGURED",
            "Cooking request unavailable", operationId = operation)
    }
}

/** Guest-token cooking backed only by the configured GuestCookingStore. The HTTP layer
 * supplies no principal, account fallback, planning result or feature permission. */
internal suspend fun ApplicationCall.guestCookingOperation(
    operation: String,
    configuration: GuestHttpConfiguration,
    validator: ContractBodyValidator,
) {
    try {
        currentCoroutineContext().ensureActive()
        val store = configuration.cooking ?: throw CookingHttpFailure(503, "NOT_CONFIGURED")
        val input = CookingHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        if (input.bearer.deviceSessionId != null) throw CookingHttpFailure(401, "UNAUTHENTICATED")
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        val reply = runInterruptible(configuration.databaseDispatcher) {
            input.bearer.token.use { token -> when (operation) {
                "createCookSession" -> guestCookingReply(store.createCookSession(token, input.key!!, body!!))
                "getCookSession" -> store.getCookSession(token, input.sessionId!!)
                "updateCookSession" -> guestCookingReply(store.updateCookSession(
                    token, input.key!!, input.sessionId!!, input.ifMatch!!, body!!))
                "completeCookSession" -> guestCookingReply(store.completeCookSession(
                    token, input.key!!, input.sessionId!!, body!!))
                else -> error("Unsupported guest cooking operation")
            } }
        }
        currentCoroutineContext().ensureActive()
        val text = validateCookingReply(operation, reply, validator, store.policy.maxResponseBytes)
        response.headers.append(HttpHeaders.ETag, checkNotNull(reply.etag))
        respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: CookingHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code,
            "Cooking request unavailable", operationId = operation,
            retryAfterSeconds = failure.retryAfterSeconds)
    } catch (failure: GuestSessionFailure) {
        currentCoroutineContext().ensureActive()
        val mapped = guestFailureHttp(operation, failure.code)
        problem(validator, HttpStatusCode.fromValue(mapped.status), mapped.code,
            "Cooking request unavailable", operationId = operation)
    } catch (failure: CookingFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name,
            "Cooking request unavailable", operationId = operation)
    } catch (_: CommitOutcomeUnknown) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN",
            "Cooking outcome requires reconciliation", operationId = operation)
    }
}

private fun guestCookingReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw CookingHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw CookingHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw CookingHttpFailure(409, "COMMAND_INCOMPLETE")
}
