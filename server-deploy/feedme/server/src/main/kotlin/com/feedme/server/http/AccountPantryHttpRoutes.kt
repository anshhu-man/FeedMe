package com.feedme.server.http

import com.feedme.core.ports.*
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.kitchen.KitchenFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.coroutines.*

/** Signature verification is only ingress. The concrete store repeats current provider,
 * account, registered-device, ready-profile and terms checks inside each DB transaction,
 * including original retries and after a body/receipt/catalog wait.
 */
internal suspend fun ApplicationCall.accountPantryOperation(operation: String,
    configuration: AccountPantryHttpConfiguration, validator: ContractBodyValidator) {
    try {
        check(operation in pantryHttpOperations)
        currentCoroutineContext().ensureActive()
        val input = KitchenHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        val device = input.bearer.deviceSessionId ?: throw KitchenHttpFailure(401, "UNAUTHENTICATED")
        val verified = configuration.verifier.verify(input.bearer.token)
        currentCoroutineContext().ensureActive()
        val subject = when (verified) {
            is PortResult.Value -> verified.value
            is PortResult.Failure -> when (verified.reason) {
                FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION ->
                    throw KitchenHttpFailure(401, "UNAUTHENTICATED")
                else -> throw KitchenHttpFailure(503, "AUTHENTICATION_UNAVAILABLE")
            }
        }
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        val reply = runInterruptible(configuration.databaseDispatcher) {
            when (operation) {
                "listPantry" -> configuration.store.listPantry(subject, device, input.cursor, input.limit)
                "upsertPantryItem" -> kitchenReply(configuration.store.upsertPantryItem(subject, device, input.key!!, body!!))
                "removePantryItem" -> kitchenReply(configuration.store.removePantryItem(subject, device,
                    input.key!!, input.ingredientId!!, input.ifMatch!!))
                else -> error("Unsupported account pantry operation")
            }
        }
        currentCoroutineContext().ensureActive()
        val text = validateKitchenReply(operation, reply, validator, configuration.store.policy.maxResponseBytes)
        reply.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        if (text == null) respond(HttpStatusCode.NoContent)
        else respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: KitchenHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Pantry request unavailable",
            operationId = operation, retryAfterSeconds = failure.retryAfterSeconds)
    } catch (failure: KitchenFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name,
            "Pantry request unavailable", operationId = operation)
    } catch (_: CommitOutcomeUnknown) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN",
            "Pantry outcome requires reconciliation", operationId = operation)
    }
}
