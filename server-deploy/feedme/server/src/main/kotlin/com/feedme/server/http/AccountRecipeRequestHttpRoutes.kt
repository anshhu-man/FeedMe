package com.feedme.server.http

import com.feedme.contracts.CanonicalFormats
import com.feedme.contracts.WireDecodingException
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandResult
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.StoredReply
import com.feedme.server.social.reciperequests.RecipeRequestFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal val accountRecipeRequestHttpOperations = setOf("requestRecipe", "getRecipeRequest", "respondToRecipeRequest", "cancelRecipeRequest")
private val recipeRequestBodies = setOf("requestRecipe", "respondToRecipeRequest")
private val recipeRequestConditions = setOf("respondToRecipeRequest", "cancelRecipeRequest")

internal class AccountRecipeRequestHttpInput private constructor(val operation: String, val token: SecretText, val device: UUID,
    val key: UUID?, val id: UUID?, val ifMatch: String?, private val contentLength: Long?, private val mediaType: String?) {
    override fun toString() = "AccountRecipeRequestHttpInput(<redacted>)"
    suspend fun readBody(channel: ByteReadChannel, validator: ContractBodyValidator): JsonObject? {
        val hasBody = operation in recipeRequestBodies
        val bytes = readBoundedHttpBody(channel, if (hasBody) AccountRecipeRequestHttpConfiguration.MAX_REQUEST_BYTES else 0, contentLength, ::invalidRecipeRequest)
        return try {
            if (!hasBody) { if (bytes.isNotEmpty()) invalidRecipeRequest(); null }
            else {
                if (bytes.isEmpty()) invalidRecipeRequest()
                val document = try { WireDocument.decode(bytes, WireLimits(AccountRecipeRequestHttpConfiguration.MAX_REQUEST_BYTES, 8)) }
                    catch (_: WireDecodingException) { invalidRecipeRequest() }
                if (validator.validateRequest(operation, bytes, mediaType) != BodyValidationResult.Valid) throw RecipeRequestHttpFailure(422, "INPUT_INVALID")
                Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
            }
        } finally { bytes.fill(0) }
    }
    companion object {
        fun parse(operation: String, headers: Headers, query: Parameters, paths: Parameters): AccountRecipeRequestHttpInput {
            if (operation !in accountRecipeRequestHttpOperations || query.names().isNotEmpty()) invalidRecipeRequest()
            fun header(name: String): String? = headers.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidRecipeRequest(); it.single()
            }
            val authorization = header(HttpHeaders.Authorization)
            val device = header("X-Device-Session")
            if (authorization == null || authorization.length > 16391 || device == null) throw RecipeRequestHttpFailure(401, "UNAUTHENTICATED")
            val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE).matchEntire(authorization)?.groupValues?.get(1)
                ?: throw RecipeRequestHttpFailure(401, "UNAUTHENTICATED")
            if (token.length !in 1..16384 || !token.matches(Regex("[A-Za-z0-9._~+/-]+=*"))) throw RecipeRequestHttpFailure(401, "UNAUTHENTICATED")
            val keyText = header("Idempotency-Key")
            val key = if (operation != "getRecipeRequest") keyText?.let(::uuid) ?: invalidRecipeRequest()
                else { if (keyText != null) invalidRecipeRequest(); null }
            val ifMatch = header(HttpHeaders.IfMatch)
            if (operation in recipeRequestConditions) {
                if (ifMatch == null) throw RecipeRequestHttpFailure(428, "PRECONDITION_REQUIRED")
                if (!ifMatch.matches(Regex("\"[1-9][0-9]{0,18}\""))) invalidRecipeRequest()
            } else if (ifMatch != null) invalidRecipeRequest()
            if (header(HttpHeaders.IfNoneMatch) != null) invalidRecipeRequest()
            val expectedPaths = if (operation == "requestRecipe") emptySet() else setOf("recipeRequestId")
            if (paths.names() != expectedPaths) invalidRecipeRequest()
            val id = if (operation == "requestRecipe") null else paths.getAll("recipeRequestId")?.let {
                if (it.size != 1) invalidRecipeRequest(); uuid(it.single())
            } ?: invalidRecipeRequest()
            val hasBody = operation in recipeRequestBodies
            val length = header(HttpHeaders.ContentLength)?.let {
                if (!it.matches(Regex("[0-9]{1,20}"))) invalidRecipeRequest()
                it.toLongOrNull()?.takeIf { size -> size <= AccountRecipeRequestHttpConfiguration.MAX_REQUEST_BYTES } ?: invalidRecipeRequest()
            }
            val transfer = header(HttpHeaders.TransferEncoding)
            if (transfer != null && (transfer.lowercase() != "chunked" || length != null || !hasBody)) invalidRecipeRequest()
            if (header(HttpHeaders.ContentEncoding)?.lowercase()?.let { it != "identity" } == true) invalidRecipeRequest()
            val media = header(HttpHeaders.ContentType)
            if (hasBody) {
                if (media == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(media)) throw RecipeRequestHttpFailure(400, "UNSUPPORTED_MEDIA")
            } else if (media != null || (length != null && length != 0L)) invalidRecipeRequest()
            return AccountRecipeRequestHttpInput(operation, SecretText(token), uuid(device), key, id, ifMatch, length, media)
        }
        private fun uuid(value: String): UUID { if (!CanonicalFormats.accepts("uuid", value)) invalidRecipeRequest(); return UUID.fromString(value) }
    }
}

internal suspend fun ApplicationCall.accountRecipeRequestOperation(operation: String, configuration: AccountRecipeRequestHttpConfiguration, validator: ContractBodyValidator) {
    try {
        currentCoroutineContext().ensureActive()
        val input = AccountRecipeRequestHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        val verified = try { configuration.verifier.verify(input.token) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw RecipeRequestHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
        currentCoroutineContext().ensureActive()
        val subject = when (verified) {
            is PortResult.Value -> verified.value
            is PortResult.Failure -> if (verified.reason in setOf(FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION))
                throw RecipeRequestHttpFailure(401, "UNAUTHENTICATED") else throw RecipeRequestHttpFailure(503, "AUTHENTICATION_UNAVAILABLE")
        }
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        val reply = runInterruptible(configuration.databaseDispatcher) {
            val store = configuration.store
            when (operation) {
                "requestRecipe" -> recipeRequestReply(store.requestRecipe(subject, input.device, input.key!!, body!!))
                "getRecipeRequest" -> store.getRecipeRequest(subject, input.device, input.id!!)
                "respondToRecipeRequest" -> recipeRequestReply(store.respondToRecipeRequest(subject, input.device, input.key!!, input.id!!, input.ifMatch!!, body!!))
                "cancelRecipeRequest" -> recipeRequestReply(store.cancelRecipeRequest(subject, input.device, input.key!!, input.id!!, input.ifMatch!!))
                else -> error("Unsupported recipe request operation")
            }
        }
        currentCoroutineContext().ensureActive()
        val text = validateAccountRecipeRequestReply(operation, reply, validator, configuration.store.policy.maxResponseBytes)
        if (text == null) respond(HttpStatusCode.NoContent)
        else {
            response.headers.append(HttpHeaders.ETag, checkNotNull(reply.etag))
            respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: RecipeRequestHttpFailure) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Recipe request unavailable", operationId = operation) }
    catch (failure: RecipeRequestFailure) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Recipe request unavailable", operationId = operation) }
    catch (_: CommitOutcomeUnknown) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN", "Recipe request outcome requires reconciliation", operationId = operation) }
}
internal fun validateAccountRecipeRequestReply(operation: String, reply: StoredReply, validator: ContractBodyValidator, maximumBytes: Int): String? {
    check(operation in accountRecipeRequestHttpOperations && maximumBytes in 1..262144)
    if (operation == "cancelRecipeRequest") {
        check(reply.status == 204 && reply.body == null && reply.etag == null && validator.validateResponse(operation, 204, null, null) == BodyValidationResult.Valid)
        return null
    }
    check(reply.status == if (operation == "requestRecipe") 201 else 200)
    val body = checkNotNull(reply.body).jsonObject; val text = body.toString(); val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    check(bytes.size <= maximumBytes && validator.validateResponse(operation, reply.status, bytes, "application/json") == BodyValidationResult.Valid)
    val etag = checkNotNull(reply.etag); check(etag.matches(Regex("\"[1-9][0-9]{0,18}\"")))
    check(BigDecimal(etag.drop(1).dropLast(1)).compareTo(BigDecimal(body.getValue("version").jsonPrimitive.content)) == 0)
    return text
}
private fun recipeRequestReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw RecipeRequestHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw RecipeRequestHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw RecipeRequestHttpFailure(409, "COMMAND_INCOMPLETE")
}
internal class RecipeRequestHttpFailure(val status: Int, val code: String) : RuntimeException("Recipe request HTTP unavailable")
private fun invalidRecipeRequest(): Nothing = throw RecipeRequestHttpFailure(400, "INVALID_REQUEST")
