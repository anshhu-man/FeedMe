package com.feedme.server.http

import com.feedme.core.ports.*
import com.feedme.server.auth.*
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.cooking.CookingFailure
import com.feedme.server.db.*
import com.feedme.server.memory.SavedRecipeFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.*
import kotlinx.coroutines.*

internal suspend fun ApplicationCall.accountCookingOperation(operation: String,
    configuration: AccountCookingHttpConfiguration, validator: ContractBodyValidator) {
    try {
        val input = CookingHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        val device = input.bearer.deviceSessionId ?: throw AccountMealHttpFailure(401, "UNAUTHENTICATED")
        val subject = accountMealSubject(configuration.verifier, input.bearer.token)
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        val store = configuration.store
        val reply = runInterruptible(configuration.databaseDispatcher) {
            when (operation) {
                "createCookSession" -> accountMealReply(store.createCookSession(subject, device, input.key!!, body!!))
                "getCookSession" -> store.getCookSession(subject, device, input.sessionId!!)
                "updateCookSession" -> accountMealReply(store.updateCookSession(subject, device, input.key!!, input.sessionId!!, input.ifMatch!!, body!!))
                "completeCookSession" -> accountMealReply(store.completeCookSession(subject, device, input.key!!, input.sessionId!!, body!!))
                else -> error("Unsupported account cooking operation")
            }
        }
        currentCoroutineContext().ensureActive()
        val text = validateCookingReply(operation, reply, validator, store.policy.maxResponseBytes)
        response.headers.append(HttpHeaders.ETag, checkNotNull(reply.etag))
        respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
    } catch (f: CancellationException) { throw f }
    catch (f: AccountMealHttpFailure) { accountMealProblem(validator, operation, f.status, f.code) }
    catch (f: CookingHttpFailure) { accountMealProblem(validator, operation, f.status, f.code) }
    catch (f: CookingFailure) { accountMealProblem(validator, operation, f.code.status, f.code.name) }
    catch (_: CommitOutcomeUnknown) { accountMealProblem(validator, operation, 503, "OUTCOME_UNKNOWN") }
}

internal suspend fun ApplicationCall.accountSavedRecipeOperation(operation: String,
    configuration: AccountSavedRecipeHttpConfiguration, validator: ContractBodyValidator) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    try {
        val input = SavedRecipeHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        val device = input.bearer.deviceSessionId ?: throw AccountMealHttpFailure(401, "UNAUTHENTICATED")
        val subject = accountMealSubject(configuration.verifier, input.bearer.token)
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        val store = configuration.store
        val reply = runInterruptible(configuration.databaseDispatcher) {
            when (operation) {
                "saveRecipe" -> accountMealReply(store.saveRecipe(subject, device, input.key!!, body!!))
                "savePostRecipe" -> accountMealReply(store.savePostRecipe(subject, device, input.key!!, input.postId!!, body!!))
                "getSavedRecipe" -> store.getSavedRecipe(subject, device, input.savedRecipeId!!)
                "listSavedRecipes" -> store.listSavedRecipes(subject, device, input.q, input.cursor, input.limit)
                "deleteSavedRecipe" -> accountMealReply(store.deleteSavedRecipe(subject, device, input.key!!, input.savedRecipeId!!, input.ifMatch!!))
                "listCollections" -> store.listCollections(subject, device, input.cursor, input.limit)
                "getCollection" -> store.getCollection(subject, device, input.collectionId!!, input.cursor, input.limit)
                "createCollection" -> accountMealReply(store.createCollection(subject,device,input.key!!,body!!))
                "updateCollection" -> accountMealReply(store.updateCollection(subject,device,input.key!!,input.collectionId!!,input.ifMatch!!,body!!))
                "deleteCollection" -> accountMealReply(store.deleteCollection(subject,device,input.key!!,input.collectionId!!,input.ifMatch!!))
                "addCollectionItem" -> accountMealReply(store.addCollectionItem(subject,device,input.key!!,input.collectionId!!,body!!))
                "removeCollectionItem" -> accountMealReply(store.removeCollectionItem(subject,device,input.key!!,input.collectionId!!,input.savedRecipeId!!,input.ifMatch!!))
                else -> error("Unsupported account saved operation")
            }
        }
        currentCoroutineContext().ensureActive()
        val text = validateSavedRecipeReply(operation, reply, validator, store.policy.maxResponseBytes)
        reply.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        if (reply.status == 204) respond(HttpStatusCode.NoContent)
        else respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
    } catch (f: CancellationException) { throw f }
    catch (f: AccountMealHttpFailure) { accountMealProblem(validator, operation, f.status, f.code) }
    catch (f: SavedRecipeHttpFailure) { accountMealProblem(validator, operation, f.status, f.code) }
    catch (f: SavedRecipeFailure) { accountMealProblem(validator, operation, f.code.status, f.code.name) }
    catch (_: CommitOutcomeUnknown) { accountMealProblem(validator, operation, 503, "OUTCOME_UNKNOWN") }
}
private suspend fun accountMealSubject(verifier: SupabaseUserAccessVerifier, token: SecretText): VerifiedSupabaseSubject {
    currentCoroutineContext().ensureActive()
    val result = verifier.verify(token)
    currentCoroutineContext().ensureActive()
    return when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> throw AccountMealHttpFailure(when (result.reason) {
            FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION -> 401
            else -> 503
        }, if (result.reason in setOf(FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION))
            "UNAUTHENTICATED" else "AUTHENTICATION_UNAVAILABLE")
    }
}
private fun accountMealReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw AccountMealHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw AccountMealHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw AccountMealHttpFailure(409, "COMMAND_INCOMPLETE")
}
private suspend fun ApplicationCall.accountMealProblem(validator: ContractBodyValidator, operation: String, status: Int, code: String) {
    currentCoroutineContext().ensureActive()
    problem(validator, HttpStatusCode.fromValue(status), code, "Account meal operation unavailable", operationId = operation)
}
private class AccountMealHttpFailure(val status: Int, val code: String) : RuntimeException("Account meal request unavailable")
