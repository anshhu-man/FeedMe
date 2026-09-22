package com.feedme.server.http

import com.feedme.core.ports.*
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.planning.PlanningServiceFailure
import com.feedme.server.planning.PlanningFailureCode
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import kotlinx.coroutines.*

internal suspend fun ApplicationCall.accountPlanningOperation(operation: String,
    configuration: AccountPlanningHttpConfiguration, validator: ContractBodyValidator) {
    try {
        currentCoroutineContext().ensureActive()
        val input = PlanningHttpInput.parseAccount(operation, request.headers, request.queryParameters, parameters)
        val device = input.bearer.deviceSessionId ?: throw PlanningHttpFailure(401, "UNAUTHENTICATED")
        val verified = configuration.verifier.verify(input.bearer.token)
        currentCoroutineContext().ensureActive()
        val subject = when (verified) {
            is PortResult.Value -> verified.value
            is PortResult.Failure -> when (verified.reason) {
                FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION ->
                    throw PlanningHttpFailure(401, "UNAUTHENTICATED")
                else -> throw PlanningHttpFailure(503, "AUTHENTICATION_UNAVAILABLE")
            }
        }
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        val result = runInterruptible(configuration.databaseDispatcher) {
            when (operation) {
                "createPlan" -> accountPlanningReply(configuration.store.createPlan(subject, device, input.key!!, body!!))
                "nextPlan" -> accountPlanningReply(configuration.store.nextPlan(subject, device, input.key!!, input.planId!!, body!!))
                "simplifyPlan" -> accountPlanningReply(configuration.store.simplifyPlan(subject, device, input.key!!,
                    input.planId!!, input.ifMatch!!, body!!))
                "adaptPlan" -> accountPlanningReply(configuration.store.adaptPlan(subject, device, input.key!!,
                    input.planId!!, input.ifMatch!!, body!!))
                "getPlan" -> configuration.store.getPlan(subject, device, input.planId!!)
                "getPlanExplanation" -> configuration.store.getPlanExplanation(subject, device, input.planId!!, input.cursor, input.limit)
                "listRecipes" -> (configuration.recipes ?: throw PlanningServiceFailure(PlanningFailureCode.NOT_CONFIGURED))
                    .list(subject, device, input.query, input.cursor, input.limit)
                "getRecipeVersion" -> (configuration.recipes ?: throw PlanningServiceFailure(PlanningFailureCode.NOT_CONFIGURED))
                    .get(subject, device, input.recipeId!!, input.recipeVersionId!!)
                else -> error("Unsupported account planning operation")
            }
        }
        currentCoroutineContext().ensureActive()
        val text = validatePlanningReply(operation, result, validator)
        result.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(result.status))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: PlanningHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Planning request unavailable",
            operationId = operation, retryAfterSeconds = failure.retryAfterSeconds)
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

private fun accountPlanningReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw PlanningHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw PlanningHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw PlanningHttpFailure(409, "COMMAND_INCOMPLETE")
}
