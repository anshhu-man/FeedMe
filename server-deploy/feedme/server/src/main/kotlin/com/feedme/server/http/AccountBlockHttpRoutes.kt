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
import com.feedme.server.social.BlockFailure
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

internal val accountBlockHttpOperations = setOf("blockUser", "unblockUser", "listBlocks")

internal class AccountBlockHttpInput private constructor(
    val operation: String, val token: SecretText, val deviceSessionId: UUID,
    val key: UUID?, val targetUserId: UUID?, val ifMatch: String?, val cursor: String?, val limit: Int,
    val contentLength: Long?, val mediaType: String?,
) {
    val hasBody: Boolean get() = operation == "blockUser"
    override fun toString() = "AccountBlockHttpInput(<redacted>)"

    suspend fun readBody(channel: ByteReadChannel, validator: ContractBodyValidator): JsonObject? {
        val bytes = readBoundedHttpBody(channel, if (hasBody) AccountBlockHttpConfiguration.MAX_REQUEST_BYTES else 0,
            contentLength, ::invalidBlockRequest)
        return try { body(bytes, validator) } finally { bytes.fill(0) }
    }

    fun body(bytes: ByteArray, validator: ContractBodyValidator): JsonObject? {
        if (!hasBody) { if (bytes.isNotEmpty()) invalidBlockRequest(); return null }
        if (bytes.isEmpty() || bytes.size > AccountBlockHttpConfiguration.MAX_REQUEST_BYTES) invalidBlockRequest()
        val document = try { WireDocument.decode(bytes, WireLimits(AccountBlockHttpConfiguration.MAX_REQUEST_BYTES, 8)) }
            catch (_: WireDecodingException) { invalidBlockRequest() }
        if (validator.validateRequest(operation, bytes, mediaType) != BodyValidationResult.Valid)
            throw AccountBlockHttpFailure(422, "INPUT_INVALID")
        return Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
    }

    companion object {
        fun parse(operation: String, headers: Headers, query: Parameters, paths: Parameters): AccountBlockHttpInput {
            if (operation !in accountBlockHttpOperations) invalidBlockRequest()
            fun header(name: String): String? = headers.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidBlockRequest()
                it.single()
            }
            val authorization = header(HttpHeaders.Authorization)
            if (authorization?.contains(',') == true) invalidBlockRequest()
            val device = header("X-Device-Session")
            if (authorization == null || authorization.length > 16_391 || device == null)
                throw AccountBlockHttpFailure(401, "UNAUTHENTICATED")
            val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE)
                .matchEntire(authorization)?.groupValues?.get(1) ?: throw AccountBlockHttpFailure(401, "UNAUTHENTICATED")
            if (token.length !in 1..16_384 || !Regex("[A-Za-z0-9._~+/-]+=*").matches(token))
                throw AccountBlockHttpFailure(401, "UNAUTHENTICATED")
            val deviceId = uuid(device)
            val keyText = header("Idempotency-Key")
            val key = if (operation == "listBlocks") {
                if (keyText != null) invalidBlockRequest(); null
            } else keyText?.let(::uuid) ?: invalidBlockRequest()
            val ifMatch = header(HttpHeaders.IfMatch)
            if (operation == "unblockUser") {
                if (ifMatch == null) throw AccountBlockHttpFailure(428, "PRECONDITION_REQUIRED")
                if (!Regex("\"[0-9]{1,64}\"").matches(ifMatch)) invalidBlockRequest()
            } else if (ifMatch != null) invalidBlockRequest()
            if (header(HttpHeaders.IfNoneMatch) != null) invalidBlockRequest()
            val allowedQuery = if (operation == "listBlocks") setOf("cursor", "limit") else emptySet()
            if (!allowedQuery.containsAll(query.names())) invalidBlockRequest()
            fun parameter(name: String): String? = query.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidBlockRequest()
                it.single()
            }
            val cursor = parameter("cursor")?.also { if (it.length !in 1..2048) invalidBlockRequest() }
            val limit = parameter("limit")?.let {
                if (!Regex("[1-9][0-9]?").matches(it)) invalidBlockRequest()
                it.toInt().also { number -> if (number !in 1..50) invalidBlockRequest() }
            } ?: 20
            // Ktor's merged call parameters may also contain the already-checked query.
            val allowedPaths = (if (operation == "unblockUser") setOf("userId") else emptySet()) + query.names()
            if (!allowedPaths.containsAll(paths.names())) invalidBlockRequest()
            val target = if (operation == "unblockUser") paths.getAll("userId")?.let {
                if (it.size != 1) invalidBlockRequest(); uuid(it.single())
            } ?: invalidBlockRequest() else null
            val length = header(HttpHeaders.ContentLength)?.let {
                if (!Regex("[0-9]{1,20}").matches(it)) invalidBlockRequest()
                it.toLongOrNull()?.takeIf { size -> size <= AccountBlockHttpConfiguration.MAX_REQUEST_BYTES } ?: invalidBlockRequest()
            }
            val hasBody = operation == "blockUser"
            val transfer = header(HttpHeaders.TransferEncoding)
            if (transfer != null && (transfer.lowercase() != "chunked" || length != null || !hasBody)) invalidBlockRequest()
            val encoding = header(HttpHeaders.ContentEncoding)
            if (encoding != null && encoding.lowercase() != "identity") invalidBlockRequest()
            val media = header(HttpHeaders.ContentType)
            if (hasBody) {
                if (media == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(media))
                    throw AccountBlockHttpFailure(400, "UNSUPPORTED_MEDIA")
            } else if (media != null || (length != null && length != 0L)) invalidBlockRequest()
            return AccountBlockHttpInput(operation, SecretText(token), deviceId, key, target, ifMatch, cursor, limit, length, media)
        }

        private fun uuid(value: String): UUID {
            if (!CanonicalFormats.accepts("uuid", value)) invalidBlockRequest()
            return UUID.fromString(value)
        }
    }
}

internal suspend fun ApplicationCall.accountBlockOperation(operation: String, configuration: AccountBlockHttpConfiguration,
    validator: ContractBodyValidator) {
    try {
        currentCoroutineContext().ensureActive()
        val input = AccountBlockHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        val verified = try { configuration.verifier.verify(input.token) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw AccountBlockHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
        currentCoroutineContext().ensureActive()
        val subject = when (verified) {
            is PortResult.Value -> verified.value
            is PortResult.Failure -> when (verified.reason) {
                FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION ->
                    throw AccountBlockHttpFailure(401, "UNAUTHENTICATED")
                else -> throw AccountBlockHttpFailure(503, "AUTHENTICATION_UNAVAILABLE")
            }
        }
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        val reply = runInterruptible(configuration.databaseDispatcher) {
            when (operation) {
                "blockUser" -> blockReply(configuration.store.blockUser(subject, input.deviceSessionId, input.key!!, body!!))
                "unblockUser" -> blockReply(configuration.store.unblockUser(subject, input.deviceSessionId, input.key!!,
                    input.targetUserId!!, input.ifMatch!!))
                "listBlocks" -> configuration.store.listBlocks(subject, input.deviceSessionId, input.cursor, input.limit)
                else -> error("Unsupported account block operation")
            }
        }
        currentCoroutineContext().ensureActive()
        val text = validateAccountBlockReply(operation, reply, validator, configuration.store.policy.maxResponseBytes)
        reply.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        if (text == null) respond(HttpStatusCode.NoContent)
        else respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: AccountBlockHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Block request unavailable", operationId = operation)
    } catch (failure: BlockFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Block request unavailable", operationId = operation)
    } catch (_: CommitOutcomeUnknown) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN", "Block outcome requires reconciliation", operationId = operation)
    }
}

internal fun validateAccountBlockReply(operation: String, reply: StoredReply, validator: ContractBodyValidator,
    maximumBytes: Int): String? {
    check(operation in accountBlockHttpOperations && maximumBytes in 1..262_144)
    val status = if (operation == "unblockUser") 204 else 200
    check(reply.status == status)
    if (status == 204) {
        check(reply.body == null && reply.etag == null && validator.validateResponse(operation, status, null, null) == BodyValidationResult.Valid)
        return null
    }
    val text = reply.body?.toString() ?: error("Missing block response")
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    check(bytes.size <= maximumBytes && validator.validateResponse(operation, status, bytes, "application/json") == BodyValidationResult.Valid)
    if (operation == "listBlocks") check(reply.etag == null)
    else {
        val etag = reply.etag ?: error("Missing block version")
        check(Regex("\"[0-9]{1,64}\"").matches(etag))
        check(BigDecimal(etag.substring(1, etag.lastIndex)).compareTo(BigDecimal(reply.body.jsonObject.getValue("version").jsonPrimitive.content)) == 0)
    }
    return text
}

private fun blockReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw AccountBlockHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw AccountBlockHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw AccountBlockHttpFailure(409, "COMMAND_INCOMPLETE")
}
internal class AccountBlockHttpFailure(val status: Int, val code: String) : RuntimeException("Account block HTTP request unavailable")
private fun invalidBlockRequest(): Nothing = throw AccountBlockHttpFailure(400, "INVALID_REQUEST")
