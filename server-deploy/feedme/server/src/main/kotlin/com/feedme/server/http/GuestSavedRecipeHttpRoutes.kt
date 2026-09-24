package com.feedme.server.http

import com.feedme.contracts.CanonicalFormats
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandResult
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.StoredReply
import com.feedme.server.guest.GuestSessionFailure
import com.feedme.server.memory.SavedRecipeFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

internal val guestSavedRecipeHttpOperations = savedRecipeHttpOperations

/** Chooses account or guest Saved authority before body consumption. Device-header presence
 * never authenticates a caller and a missing chosen adapter never falls through. */
internal suspend fun ApplicationCall.credentialSavedRecipeOperation(
    operation: String,
    account: AccountSavedRecipeHttpConfiguration?,
    guest: GuestHttpConfiguration?,
    validator: ContractBodyValidator,
) {
    require(operation in savedRecipeHttpOperations)
    val devices = request.headers.getAll("X-Device-Session")
    if (devices != null && (devices.size != 1 || devices.single().any(Char::isISOControl) ||
            !CanonicalFormats.accepts("uuid", devices.single()))) {
        problem(validator, HttpStatusCode.BadRequest, "INVALID_REQUEST",
            "Saved recipe request unavailable", operationId = operation)
        return
    }
    when {
        devices != null && account != null -> accountSavedRecipeOperation(operation, account, validator)
        devices == null && guest?.saved != null -> guestSavedRecipeOperation(operation, guest, validator)
        devices != null -> problem(validator, HttpStatusCode.ServiceUnavailable, "NOT_CONFIGURED",
            "Saved recipe request unavailable", operationId = operation)
        account != null -> accountSavedRecipeOperation(operation, account, validator)
        else -> problem(validator, HttpStatusCode.ServiceUnavailable, "NOT_CONFIGURED",
            "Saved recipe request unavailable", operationId = operation)
    }
}

/** Guest-token basic cookbook operations. Copy permission, recipe provenance and current
 * guest authority remain owned by GuestSavedRecipeStore; HTTP supplies none of them. */
internal suspend fun ApplicationCall.guestSavedRecipeOperation(
    operation: String,
    configuration: GuestHttpConfiguration,
    validator: ContractBodyValidator,
) {
    try {
        currentCoroutineContext().ensureActive()
        val store = configuration.saved ?: throw SavedRecipeHttpFailure(503, "NOT_CONFIGURED")
        val input = SavedRecipeHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        if (input.bearer.deviceSessionId != null) throw SavedRecipeHttpFailure(401, "UNAUTHENTICATED")
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        val reply = runInterruptible(configuration.databaseDispatcher) {
            input.bearer.token.use { token -> when (operation) {
                "saveRecipe" -> guestSavedRecipeReply(store.saveRecipe(token, input.key!!, body!!))
                "getSavedRecipe" -> store.getSavedRecipe(token, input.savedRecipeId!!)
                "listSavedRecipes" -> store.listSavedRecipes(token, input.q, input.cursor, input.limit)
                "deleteSavedRecipe" -> guestSavedRecipeReply(store.deleteSavedRecipe(
                    token, input.key!!, input.savedRecipeId!!, input.ifMatch!!))
                "listCollections" -> store.listCollections(token, input.cursor, input.limit)
                "getCollection" -> store.getCollection(token, input.collectionId!!, input.cursor, input.limit)
                else -> error("Unsupported guest saved-recipe operation")
            } }
        }
        currentCoroutineContext().ensureActive()
        val text = validateSavedRecipeReply(operation, reply, validator, store.policy.maxResponseBytes)
        reply.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        if (reply.status == 204) respond(HttpStatusCode.NoContent)
        else respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: SavedRecipeHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code,
            "Saved recipe request unavailable", operationId = operation,
            retryAfterSeconds = failure.retryAfterSeconds)
    } catch (failure: GuestSessionFailure) {
        currentCoroutineContext().ensureActive()
        val mapped = guestFailureHttp(operation, failure.code)
        problem(validator, HttpStatusCode.fromValue(mapped.status), mapped.code,
            "Saved recipe request unavailable", operationId = operation)
    } catch (failure: SavedRecipeFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name,
            "Saved recipe request unavailable", operationId = operation)
    } catch (_: CommitOutcomeUnknown) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN",
            "Saved recipe outcome requires reconciliation", operationId = operation)
    }
}

private fun guestSavedRecipeReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw SavedRecipeHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw SavedRecipeHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw SavedRecipeHttpFailure(409, "COMMAND_INCOMPLETE")
}
