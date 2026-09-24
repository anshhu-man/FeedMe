package com.feedme.server.http

import com.feedme.contracts.CanonicalFormats
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandResult
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.StoredReply
import com.feedme.server.guest.GuestSessionFailure
import com.feedme.server.planning.PlanningServiceFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

internal val guestPlanningHttpOperations = setOf("createPlan", "getPlan", "getPlanExplanation")

/** Selects exactly one credential profile before either handler consumes the body. Header
 * presence is not authentication: each selected handler still performs its complete parsing
 * and authority checks, and a missing selected configuration never falls back to the other. */
internal suspend fun ApplicationCall.credentialPlanningOperation(
    operation: String,
    account: AccountPlanningHttpConfiguration?,
    guest: GuestHttpConfiguration?,
    validator: ContractBodyValidator,
) {
    require(operation in planningHttpOperations)
    val devices = request.headers.getAll("X-Device-Session")
    if (devices != null && (devices.size != 1 || devices.single().any(Char::isISOControl) ||
            !CanonicalFormats.accepts("uuid", devices.single()))) {
        problem(validator, HttpStatusCode.BadRequest, "INVALID_REQUEST", "Planning request unavailable", operationId = operation)
        return
    }
    when {
        account != null && guest?.plans != null && devices != null -> accountPlanningOperation(operation, account, validator)
        account != null && guest?.plans != null -> guestPlanningOperation(operation, guest, validator)
        account != null -> accountPlanningOperation(operation, account, validator)
        guest?.plans != null && devices == null -> guestPlanningOperation(operation, guest, validator)
        else -> problem(validator, HttpStatusCode.ServiceUnavailable, "NOT_CONFIGURED",
            "Planning request unavailable", operationId = operation)
    }
}

/** Purpose-fixed guest-token planning route. The concrete GuestPlansStore owns token,
 * lifecycle, quota, catalog and transaction rechecks; HTTP never accepts caller identity. */
internal suspend fun ApplicationCall.guestPlanningOperation(
    operation: String,
    configuration: GuestHttpConfiguration,
    validator: ContractBodyValidator,
) {
    try {
        currentCoroutineContext().ensureActive()
        val store = configuration.plans ?: throw PlanningHttpFailure(503, "NOT_CONFIGURED")
        if (operation !in store.implementedOperations) throw PlanningHttpFailure(503, "NOT_CONFIGURED")
        val input = PlanningHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        if (input.bearer.deviceSessionId != null) throw PlanningHttpFailure(401, "UNAUTHENTICATED")
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        val result = runInterruptible(configuration.databaseDispatcher) {
            input.bearer.token.use { token -> when (operation) {
                "createPlan" -> guestPlanningReply(store.createPlan(token, input.key!!, body!!))
                "getPlan" -> store.getPlan(token, input.planId!!)
                "getPlanExplanation" -> store.getPlanExplanation(token, input.planId!!, input.cursor, input.limit)
                else -> error("Unsupported guest planning operation")
            } }
        }
        currentCoroutineContext().ensureActive()
        val text = validatePlanningReply(operation, result, validator)
        result.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(result.status))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: PlanningHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code,
            "Planning request unavailable", operationId = operation, retryAfterSeconds = failure.retryAfterSeconds)
    } catch (failure: GuestSessionFailure) {
        currentCoroutineContext().ensureActive()
        val mapped = guestFailureHttp(operation, failure.code)
        problem(validator, HttpStatusCode.fromValue(mapped.status), mapped.code,
            "Planning request unavailable", operationId = operation)
    } catch (failure: PlanningServiceFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name,
            "Planning request unavailable", operationId = operation)
    } catch (_: CommitOutcomeUnknown) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN",
            "Planning outcome requires reconciliation", operationId = operation)
    }
}

private fun guestPlanningReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw PlanningHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw PlanningHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw PlanningHttpFailure(409, "COMMAND_INCOMPLETE")
}
