package com.feedme.server.http

import com.feedme.contracts.CanonicalFormats
import com.feedme.core.ports.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandResult
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.StoredReply
import com.feedme.server.social.posts.PostPlacementFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** The general wire operation is intentionally narrowed to the two actual Plate buttons. */
internal suspend fun ApplicationCall.accountPostPlacementOperation(configuration: AccountPostPlacementHttpConfiguration,
    validator: ContractBodyValidator) {
    val operation = "updatePost"
    response.headers.append(HttpHeaders.CacheControl, "private, no-store")
    try {
        fun invalid(): Nothing = throw PostPlacementHttpFailure(400, "INVALID_REQUEST")
        fun header(name: String): String? = request.headers.getAll(name)?.let {
            if (it.size != 1 || it.single().any(Char::isISOControl)) invalid(); it.single()
        }
        fun uuid(value: String?): UUID {
            if (value == null || !CanonicalFormats.accepts("uuid", value)) invalid()
            return UUID.fromString(value)
        }
        val authorization = header(HttpHeaders.Authorization) ?: throw PostPlacementHttpFailure(401, "UNAUTHENTICATED")
        val token = Regex("Bearer +([A-Za-z0-9._~+/-]+=*)", RegexOption.IGNORE_CASE).matchEntire(authorization)?.groupValues?.get(1)
            ?.takeIf { it.length in 1..16_384 } ?: throw PostPlacementHttpFailure(401, "UNAUTHENTICATED")
        val device = header("X-Device-Session")?.let(::uuid) ?: throw PostPlacementHttpFailure(401, "UNAUTHENTICATED")
        val key = uuid(header("Idempotency-Key"))
        val ifMatch = header(HttpHeaders.IfMatch) ?: throw PostPlacementHttpFailure(428, "PRECONDITION_REQUIRED")
        if (!ifMatch.matches(Regex("\"[1-9][0-9]{0,18}\"")) || ifMatch.removeSurrounding("\"").toLongOrNull() == null) invalid()
        if (request.queryParameters.names().isNotEmpty() || parameters.names() != setOf("postId")) invalid()
        val post = uuid(parameters.getAll("postId")?.singleOrNull())
        if (header(HttpHeaders.IfNoneMatch) != null) invalid()
        header(HttpHeaders.ContentEncoding)?.let { if (!it.equals("identity", true)) invalid() }
        val type = header(HttpHeaders.ContentType)
        if (type == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(type))
            throw PostPlacementHttpFailure(400, "UNSUPPORTED_MEDIA")
        val length = header(HttpHeaders.ContentLength)?.let {
            if (!it.matches(Regex("[0-9]{1,20}"))) invalid()
            it.toLongOrNull()?.takeIf { size -> size in 1..1_024 } ?: invalid()
        }
        header(HttpHeaders.TransferEncoding)?.let { if (!it.equals("chunked", true) || length != null) invalid() }
        val verified = try { configuration.verifier.verify(SecretText(token)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw PostPlacementHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
        val subject = when (verified) {
            is PortResult.Value -> verified.value
            is PortResult.Failure -> if (verified.reason in setOf(FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION))
                throw PostPlacementHttpFailure(401, "UNAUTHENTICATED") else throw PostPlacementHttpFailure(503, "AUTHENTICATION_UNAVAILABLE")
        }
        val bytes = readBoundedHttpBody(receiveChannel(), 1_024, length, ::invalid)
        val body = try {
            if (validator.validateRequest(operation, bytes, type) != BodyValidationResult.Valid)
                throw PostPlacementHttpFailure(422, "INPUT_INVALID")
            Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject.also {
                if (it.keys != setOf("keepOnPlate") || (it["keepOnPlate"] as? JsonPrimitive)?.booleanOrNull == null ||
                    it.getValue("keepOnPlate").jsonPrimitive.isString) throw PostPlacementHttpFailure(422, "INPUT_INVALID")
            }
        } finally { bytes.fill(0) }
        currentCoroutineContext().ensureActive()
        val result = runInterruptible(configuration.databaseDispatcher) {
            configuration.store.updatePost(subject, device, key, post, ifMatch, body)
        }
        currentCoroutineContext().ensureActive()
        val reply = placementReply(result)
        val text = reply.body?.toString() ?: error("Missing placement receipt")
        check(reply.status == 200 && reply.etag?.matches(Regex("\"[1-9][0-9]{0,18}\"")) == true &&
            text.encodeToByteArray().size <= configuration.store.policy.maxResponseBytes &&
            validator.validateResponse(operation, 200, text.encodeToByteArray(), "application/json") == BodyValidationResult.Valid)
        response.headers.append(HttpHeaders.ETag, checkNotNull(reply.etag))
        respondText(text, ContentType.Application.Json, HttpStatusCode.OK)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: PostPlacementHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Post placement unavailable", operationId = operation)
    } catch (failure: PostPlacementFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Post placement unavailable", operationId = operation)
    } catch (_: CommitOutcomeUnknown) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN", "Post placement outcome requires reconciliation", operationId = operation)
    }
}
private fun placementReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw PostPlacementHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw PostPlacementHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw PostPlacementHttpFailure(409, "COMMAND_INCOMPLETE")
}
internal class PostPlacementHttpFailure(val status: Int, val code: String) : RuntimeException("Post placement HTTP unavailable")
