package com.feedme.server.http

import com.feedme.contracts.*
import com.feedme.core.ports.SecretText
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandResult
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.StoredReply
import com.feedme.server.guest.GuestSessionFailure
import com.feedme.server.memory.FeedbackFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*

internal val guestFeedbackHttpOperations = setOf("createFeedback", "updateFeedback", "deleteFeedback")
private val guestFeedbackBodyOperations = setOf("createFeedback", "updateFeedback")

private class GuestFeedbackHttpInput private constructor(
    val operation: String,
    val token: SecretText,
    val key: UUID,
    val id: UUID?,
    val ifMatch: String?,
    private val contentLength: Long?,
    private val mediaType: String?,
) {
    suspend fun readBody(channel: ByteReadChannel, validator: ContractBodyValidator): JsonObject? {
        val hasBody = operation in guestFeedbackBodyOperations
        val bytes = readBoundedHttpBody(channel,
            if (hasBody) AccountMemoryHttpConfiguration.MAX_REQUEST_BYTES else 0,
            contentLength, ::invalidGuestFeedback)
        return try {
            if (!hasBody) { if (bytes.isNotEmpty()) invalidGuestFeedback(); null }
            else {
                if (bytes.isEmpty()) invalidGuestFeedback()
                val document = try { WireDocument.decode(bytes,
                    WireLimits(AccountMemoryHttpConfiguration.MAX_REQUEST_BYTES, 32)) }
                    catch (_: WireDecodingException) { invalidGuestFeedback() }
                if (validator.validateRequest(operation, bytes, mediaType) != BodyValidationResult.Valid)
                    throw GuestFeedbackHttpFailure(422, "INPUT_INVALID")
                Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject.also {
                    if (it.isEmpty()) throw GuestFeedbackHttpFailure(422, "INPUT_INVALID")
                }
            }
        } finally { bytes.fill(0) }
    }

    companion object {
        fun parse(operation: String, headers: Headers, query: Parameters,
            paths: Parameters): GuestFeedbackHttpInput {
            if (operation !in guestFeedbackHttpOperations || query.names().isNotEmpty()) invalidGuestFeedback()
            fun header(name: String): String? = headers.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidGuestFeedback()
                it.single()
            }
            if (header(HttpHeaders.Upgrade) != null || header("HTTP2-Settings") != null ||
                header(HttpHeaders.Connection)?.split(',')?.any { it.trim().equals("upgrade", true) } == true)
                invalidGuestFeedback()
            val authorization = header(HttpHeaders.Authorization) ?: unauthenticatedGuestFeedback()
            if (authorization.contains(',')) invalidGuestFeedback()
            if (authorization.length > 16_391) unauthenticatedGuestFeedback()
            val raw = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE)
                .matchEntire(authorization)?.groupValues?.get(1) ?: unauthenticatedGuestFeedback()
            if (raw.length !in 1..16_384 || !raw.matches(Regex("[A-Za-z0-9._~+/-]+=*")))
                unauthenticatedGuestFeedback()
            if (header("X-Device-Session") != null) unauthenticatedGuestFeedback()
            val key = header("Idempotency-Key")?.let(::uuid) ?: invalidGuestFeedback()
            val ifMatch = header(HttpHeaders.IfMatch)
            if (operation != "createFeedback") {
                if (ifMatch == null) throw GuestFeedbackHttpFailure(428, "PRECONDITION_REQUIRED")
                if (!ifMatch.matches(Regex("\"[1-9][0-9]{0,18}\"")) ||
                    ifMatch.removeSurrounding("\"").toLongOrNull() == null) invalidGuestFeedback()
            } else if (ifMatch != null) invalidGuestFeedback()
            if (header(HttpHeaders.IfNoneMatch) != null) invalidGuestFeedback()
            val pathNames = if (operation == "createFeedback") emptySet() else setOf("feedbackId")
            if (paths.names() != pathNames) invalidGuestFeedback()
            val id = if (pathNames.isEmpty()) null else paths.getAll("feedbackId")?.let {
                if (it.size != 1) invalidGuestFeedback(); uuid(it.single())
            } ?: invalidGuestFeedback()
            val length = header(HttpHeaders.ContentLength)?.let {
                if (!it.matches(Regex("[0-9]{1,20}"))) invalidGuestFeedback()
                it.toLongOrNull()?.takeIf { n -> n <= AccountMemoryHttpConfiguration.MAX_REQUEST_BYTES }
                    ?: invalidGuestFeedback()
            }
            val hasBody = operation in guestFeedbackBodyOperations
            val transfer = header(HttpHeaders.TransferEncoding)
            if (transfer != null && (!transfer.equals("chunked", true) || length != null || !hasBody))
                invalidGuestFeedback()
            header(HttpHeaders.ContentEncoding)?.let { if (!it.equals("identity", true)) invalidGuestFeedback() }
            val media = header(HttpHeaders.ContentType)
            if (hasBody) {
                if (media == null || !media.matches(Regex(
                        "application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?",
                        RegexOption.IGNORE_CASE)))
                    throw GuestFeedbackHttpFailure(400, "UNSUPPORTED_MEDIA")
            } else if (media != null || length != null && length != 0L) invalidGuestFeedback()
            return GuestFeedbackHttpInput(operation, SecretText(raw), key, id, ifMatch, length, media)
        }

        private fun uuid(value: String): UUID {
            if (!CanonicalFormats.accepts("uuid", value)) invalidGuestFeedback()
            return UUID.fromString(value)
        }
    }
}

internal suspend fun ApplicationCall.credentialFeedbackOperation(
    operation: String,
    account: AccountMemoryHttpConfiguration?,
    guest: GuestHttpConfiguration?,
    validator: ContractBodyValidator,
) {
    require(operation in guestFeedbackHttpOperations)
    val devices = request.headers.getAll("X-Device-Session")
    if (devices != null && (devices.size != 1 || devices.single().any(Char::isISOControl) ||
            !CanonicalFormats.accepts("uuid", devices.single()))) {
        problem(validator, HttpStatusCode.BadRequest, "INPUT_INVALID",
            "Feedback request unavailable", operationId = operation)
        return
    }
    when {
        devices != null && account != null -> accountMemoryOperation(operation, account, validator)
        devices == null && guest?.feedback != null -> guestFeedbackOperation(operation, guest, validator)
        devices != null -> problem(validator, HttpStatusCode.ServiceUnavailable, "NOT_CONFIGURED",
            "Feedback request unavailable", operationId = operation)
        account != null -> accountMemoryOperation(operation, account, validator)
        else -> problem(validator, HttpStatusCode.ServiceUnavailable, "NOT_CONFIGURED",
            "Feedback request unavailable", operationId = operation)
    }
}

internal suspend fun ApplicationCall.guestFeedbackOperation(operation: String,
    configuration: GuestHttpConfiguration, validator: ContractBodyValidator) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    try {
        val store = configuration.feedback ?: throw GuestFeedbackHttpFailure(503, "NOT_CONFIGURED")
        val input = GuestFeedbackHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        val reply = runInterruptible(configuration.databaseDispatcher) {
            input.token.use { token -> when (operation) {
                "createFeedback" -> guestFeedbackReply(store.createFeedback(token, input.key, body!!))
                "updateFeedback" -> guestFeedbackReply(store.updateFeedback(
                    token, input.key, input.id!!, input.ifMatch!!, body!!))
                "deleteFeedback" -> guestFeedbackReply(store.deleteFeedback(
                    token, input.key, input.id!!, input.ifMatch!!))
                else -> error("Unsupported guest feedback operation")
            } }
        }
        currentCoroutineContext().ensureActive()
        val text = validateAccountMemoryReply(operation, reply, validator, store.policy.maxResponseBytes)
        reply.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        if (reply.status == 204) respond(HttpStatusCode.NoContent)
        else respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: GuestFeedbackHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code,
            "Feedback request unavailable", operationId = operation)
    } catch (failure: GuestSessionFailure) {
        currentCoroutineContext().ensureActive()
        val mapped = guestFailureHttp(operation, failure.code)
        problem(validator, HttpStatusCode.fromValue(mapped.status), mapped.code,
            "Feedback request unavailable", operationId = operation)
    } catch (failure: FeedbackFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(guestFeedbackStatus(failure.code.name)),
            failure.code.name, "Feedback request unavailable", operationId = operation)
    } catch (_: CommitOutcomeUnknown) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN",
            "Feedback outcome requires reconciliation", operationId = operation)
    }
}

private fun guestFeedbackReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw GuestFeedbackHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw GuestFeedbackHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw GuestFeedbackHttpFailure(409, "COMMAND_INCOMPLETE")
}

private fun guestFeedbackStatus(code: String): Int = when (code) {
    "INPUT_INVALID" -> 422
    "UNAUTHENTICATED" -> 401
    "FORBIDDEN" -> 403
    "FEEDBACK_UNAVAILABLE", "TARGET_UNAVAILABLE" -> 404
    "VERSION_CONFLICT", "FEEDBACK_CONFLICT" -> 409
    else -> 503
}

private class GuestFeedbackHttpFailure(val status: Int, val code: String) :
    RuntimeException("Guest feedback request unavailable")
private fun invalidGuestFeedback(): Nothing = throw GuestFeedbackHttpFailure(400, "INPUT_INVALID")
private fun unauthenticatedGuestFeedback(): Nothing = throw GuestFeedbackHttpFailure(401, "UNAUTHENTICATED")
