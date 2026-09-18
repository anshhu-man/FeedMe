package com.feedme.server.http

import com.feedme.core.ports.*
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.auth.SupabaseUserAccessVerifier
import com.feedme.server.kitchen.AccountPreferencesOperations
import com.feedme.server.kitchen.KitchenFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import kotlinx.coroutines.*

internal val pendingPreferencesHttpOperations = setOf("getPreferences", "updatePreferences", "searchIngredients")
internal val accountPreferencesHttpOperations = pendingPreferencesHttpOperations

internal suspend fun ApplicationCall.pendingPreferencesOperation(operation: String,
    configuration: PendingPreferencesHttpConfiguration, validator: ContractBodyValidator) =
    selfPreferencesOperation(operation, configuration.store, configuration.verifier, configuration.databaseDispatcher, validator)

internal suspend fun ApplicationCall.accountPreferencesOperation(operation: String,
    configuration: AccountPreferencesHttpConfiguration, validator: ContractBodyValidator) =
    selfPreferencesOperation(operation, configuration.store, configuration.verifier, configuration.databaseDispatcher, validator)

private suspend fun ApplicationCall.selfPreferencesOperation(operation: String, store: AccountPreferencesOperations,
    verifier: SupabaseUserAccessVerifier, databaseDispatcher: CoroutineDispatcher, validator: ContractBodyValidator) {
    try {
        check(operation in pendingPreferencesHttpOperations)
        currentCoroutineContext().ensureActive()
        val input = KitchenHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        val device = input.bearer.deviceSessionId ?: throw KitchenHttpFailure(401, "UNAUTHENTICATED")
        val verified = verifier.verify(input.bearer.token)
        currentCoroutineContext().ensureActive()
        val subject = when (verified) {
            is PortResult.Value -> verified.value
            is PortResult.Failure -> when (verified.reason) {
                FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION -> throw KitchenHttpFailure(401, "UNAUTHENTICATED")
                else -> throw KitchenHttpFailure(503, "AUTHENTICATION_UNAVAILABLE")
            }
        }
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        // Bounded JDBC execution; a cancelled caller/lost COMMIT must retain the original key.
        // The current provider/device/profile proof runs AFTER the potentially held body read.
        val reply = runInterruptible(databaseDispatcher) {
            when (operation) {
                "getPreferences" -> store.getPreferences(subject, device)
                "searchIngredients" -> input.ingredientIds?.let { store.lookupIngredients(subject, device, it) }
                    ?: store.searchIngredients(subject, device, input.query, input.cursor, input.limit)
                "updatePreferences" -> when (val result = store.updatePreferences(subject, device, input.key!!, input.ifMatch!!, body!!)) {
                    is CommandResult.Applied -> result.reply
                    is CommandResult.Replayed -> result.reply
                    CommandResult.Mismatch -> throw KitchenHttpFailure(409, "IDEMPOTENCY_MISMATCH")
                    CommandResult.ReceiptExpired -> throw KitchenHttpFailure(410, "IDEMPOTENCY_EXPIRED")
                    CommandResult.IncompleteReceipt -> throw KitchenHttpFailure(409, "COMMAND_INCOMPLETE")
                }
                else -> error("Unsupported self-preferences operation")
            }
        }
        currentCoroutineContext().ensureActive()
        val text = checkNotNull(validateKitchenReply(operation, reply, validator, store.policy.maxResponseBytes))
        reply.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: KitchenHttpFailure) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Preferences request unavailable", operationId = operation, retryAfterSeconds = failure.retryAfterSeconds) }
    catch (failure: KitchenFailure) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Preferences request unavailable", operationId = operation) }
    catch (_: CommitOutcomeUnknown) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN", "Preferences outcome requires reconciliation", operationId = operation) }
}
