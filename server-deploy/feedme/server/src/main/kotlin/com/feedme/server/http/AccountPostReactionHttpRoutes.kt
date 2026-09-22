package com.feedme.server.http

import com.feedme.contracts.CanonicalFormats
import com.feedme.core.ports.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandResult
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.StoredReply
import com.feedme.server.social.posts.PostReactionFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal suspend fun ApplicationCall.accountPostReactionOperation(operation: String,
    configuration: AccountPostReactionHttpConfiguration, validator: ContractBodyValidator) {
    require(operation in setOf("setReaction", "removeReaction"))
    response.headers.append(HttpHeaders.CacheControl, "private, no-store")
    try {
        fun invalid(): Nothing = throw PostReactionHttpFailure(400, "INVALID_REQUEST")
        fun header(name: String): String? = request.headers.getAll(name)?.let {
            if (it.size != 1 || it.single().any(Char::isISOControl)) invalid(); it.single()
        }
        fun uuid(value: String?): UUID {
            if (value == null || !CanonicalFormats.accepts("uuid", value)) invalid()
            return UUID.fromString(value)
        }
        val authorization = header(HttpHeaders.Authorization) ?: throw PostReactionHttpFailure(401, "UNAUTHENTICATED")
        val token = Regex("Bearer +([A-Za-z0-9._~+/-]+=*)", RegexOption.IGNORE_CASE).matchEntire(authorization)?.groupValues?.get(1)
            ?.takeIf { it.length in 1..16_384 } ?: throw PostReactionHttpFailure(401, "UNAUTHENTICATED")
        val device = header("X-Device-Session")?.let(::uuid) ?: throw PostReactionHttpFailure(401, "UNAUTHENTICATED")
        val key = uuid(header("Idempotency-Key"))
        if (request.queryParameters.names().isNotEmpty() || parameters.names() != setOf("postId")) invalid()
        val post = uuid(parameters.getAll("postId")?.singleOrNull())
        if (header(HttpHeaders.IfNoneMatch) != null) invalid()
        header(HttpHeaders.ContentEncoding)?.let { if (!it.equals("identity", true)) invalid() }
        val ifMatch = header(HttpHeaders.IfMatch)
        val type = header(HttpHeaders.ContentType)
        val length = header(HttpHeaders.ContentLength)?.let {
            if (!it.matches(Regex("[0-9]{1,20}"))) invalid()
            it.toLongOrNull()?.takeIf { size -> size in 0..1024 } ?: invalid()
        }
        val transfer = header(HttpHeaders.TransferEncoding)
        if (operation == "removeReaction") {
            if (ifMatch == null) throw PostReactionHttpFailure(428, "PRECONDITION_REQUIRED")
            if (!ifMatch.matches(Regex("\"[1-9][0-9]{0,18}\"")) || ifMatch.removeSurrounding("\"").toLongOrNull() == null ||
                type != null || transfer != null || length != null && length != 0L) invalid()
        } else {
            if (ifMatch != null || length == 0L || transfer != null && (!transfer.equals("chunked", true) || length != null)) invalid()
            if (type == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(type))
                throw PostReactionHttpFailure(400, "UNSUPPORTED_MEDIA")
        }
        val verified = try { configuration.verifier.verify(SecretText(token)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw PostReactionHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
        val subject = when (verified) {
            is PortResult.Value -> verified.value
            is PortResult.Failure -> if (verified.reason in setOf(FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION))
                throw PostReactionHttpFailure(401, "UNAUTHENTICATED") else throw PostReactionHttpFailure(503, "AUTHENTICATION_UNAVAILABLE")
        }
        val bytes = readBoundedHttpBody(receiveChannel(), if (operation == "setReaction") 1024 else 0, length, ::invalid)
        val body = try {
            if (operation == "setReaction") {
                if (validator.validateRequest(operation, bytes, type) != BodyValidationResult.Valid)
                    throw PostReactionHttpFailure(422, "INPUT_INVALID")
                Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
            } else { if (bytes.isNotEmpty()) invalid(); null }
        } finally { bytes.fill(0) }
        currentCoroutineContext().ensureActive()
        val result = runInterruptible(configuration.databaseDispatcher) {
            if (operation == "setReaction") configuration.store.setReaction(subject, device, key, post, checkNotNull(body))
            else configuration.store.removeReaction(subject, device, key, post, checkNotNull(ifMatch))
        }
        currentCoroutineContext().ensureActive()
        val reply = reactionReply(result)
        if (operation == "removeReaction") {
            check(reply.status == 204 && reply.body == null && reply.etag == null && validator.validateResponse(operation, 204, null, null) == BodyValidationResult.Valid)
            respond(HttpStatusCode.NoContent)
        } else {
            val text = reply.body?.toString() ?: error("Missing reaction receipt")
            check(reply.status == 200 && reply.etag?.matches(Regex("\"[1-9][0-9]{0,18}\"")) == true &&
                text.encodeToByteArray().size <= configuration.store.policy.maxResponseBytes &&
                validator.validateResponse(operation, 200, text.encodeToByteArray(), "application/json") == BodyValidationResult.Valid)
            response.headers.append(HttpHeaders.ETag, checkNotNull(reply.etag))
            respondText(text, ContentType.Application.Json, HttpStatusCode.OK)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: PostReactionHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Post reaction unavailable", operationId = operation)
    } catch (failure: PostReactionFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Post reaction unavailable", operationId = operation)
    } catch (_: CommitOutcomeUnknown) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN", "Reaction outcome requires reconciliation", operationId = operation)
    }
}
private fun reactionReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw PostReactionHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw PostReactionHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw PostReactionHttpFailure(409, "COMMAND_INCOMPLETE")
}
internal class PostReactionHttpFailure(val status: Int, val code: String) : RuntimeException("Post reaction HTTP unavailable")
