package com.feedme.server.http

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.memory.*
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.*
import io.ktor.utils.io.ByteReadChannel
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal val accountMemoryOperations = setOf("createFeedback", "updateFeedback", "deleteFeedback",
    "listMemories", "getMemory", "updateMemory", "deleteMemory")
private val memoryBodyOperations = setOf("createFeedback", "updateFeedback", "updateMemory")
private val memoryReadOperations = setOf("listMemories", "getMemory")

internal class AccountMemoryHttpInput private constructor(val operation: String, val token: SecretText,
    val device: UUID, val key: UUID?, val id: UUID?, val ifMatch: String?, val cursor: String?, val limit: Int,
    private val contentLength: Long?, private val mediaType: String?) {
    override fun toString() = "AccountMemoryHttpInput(<redacted>)"
    suspend fun readBody(channel: ByteReadChannel, validator: ContractBodyValidator): JsonObject? {
        val hasBody = operation in memoryBodyOperations
        val bytes = readBoundedHttpBody(channel, if (hasBody) AccountMemoryHttpConfiguration.MAX_REQUEST_BYTES else 0,
            contentLength, ::invalidMemory)
        return try {
            if (!hasBody) { if (bytes.isNotEmpty()) invalidMemory(); null }
            else {
                if (bytes.isEmpty()) invalidMemory()
                val document = try { WireDocument.decode(bytes, WireLimits(AccountMemoryHttpConfiguration.MAX_REQUEST_BYTES, 32)) }
                    catch (_: WireDecodingException) { invalidMemory() }
                if (validator.validateRequest(operation, bytes, mediaType) != BodyValidationResult.Valid)
                    throw AccountMemoryHttpFailure(422, "INPUT_INVALID")
                Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject.also {
                    if (it.isEmpty()) throw AccountMemoryHttpFailure(422, "INPUT_INVALID")
                }
            }
        } finally { bytes.fill(0) }
    }
    companion object {
        fun parse(operation: String, headers: Headers, query: Parameters, paths: Parameters): AccountMemoryHttpInput {
            if (operation !in accountMemoryOperations) invalidMemory()
            val allowed = if (operation == "listMemories") setOf("cursor", "limit") else emptySet()
            if (!allowed.containsAll(query.names())) invalidMemory()
            fun parameter(name: String): String? = query.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidMemory()
                it.single().also { value -> try { value.encodeToByteArray(throwOnInvalidSequence = true) } catch (_: Exception) { invalidMemory() } }
            }
            val cursor = parameter("cursor")?.also { if (it.isEmpty() || it.length > 2048) invalidMemory() }
            val limit = parameter("limit")?.let { if (!it.matches(Regex("[0-9]{1,2}"))) invalidMemory()
                it.toIntOrNull()?.takeIf { n -> n in 1..50 } ?: invalidMemory() } ?: 20
            fun header(name: String): String? = headers.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidMemory(); it.single()
            }
            if (header(HttpHeaders.Upgrade) != null || header("HTTP2-Settings") != null ||
                header(HttpHeaders.Connection)?.split(',')?.any { it.trim().equals("upgrade", true) } == true) invalidMemory()
            val authorization = header(HttpHeaders.Authorization) ?: unauthenticatedMemory()
            if (authorization.contains(',')) invalidMemory()
            if (authorization.length > 16_391) unauthenticatedMemory()
            val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE).matchEntire(authorization)?.groupValues?.get(1)
                ?: unauthenticatedMemory()
            if (token.length !in 1..16_384 || !token.matches(Regex("[A-Za-z0-9._~+/-]+=*"))) unauthenticatedMemory()
            val device = header("X-Device-Session")?.let(::uuid) ?: unauthenticatedMemory()
            val keyText = header("Idempotency-Key")
            val key = if (operation in memoryReadOperations) { if (keyText != null) invalidMemory(); null }
                else keyText?.let(::uuid) ?: invalidMemory()
            val ifMatch = header(HttpHeaders.IfMatch)
            if (operation in setOf("updateFeedback", "deleteFeedback", "updateMemory", "deleteMemory")) {
                if (ifMatch == null) throw AccountMemoryHttpFailure(428, "PRECONDITION_REQUIRED")
                if (!ifMatch.matches(Regex("\"[1-9][0-9]{0,18}\"")) || ifMatch.removeSurrounding("\"").toLongOrNull() == null) invalidMemory()
            } else if (ifMatch != null) invalidMemory()
            if (header(HttpHeaders.IfNoneMatch) != null) invalidMemory()
            val pathName = when (operation) { "updateFeedback", "deleteFeedback" -> "feedbackId"
                "getMemory", "updateMemory", "deleteMemory" -> "memoryId"; else -> null }
            if (paths.names() - query.names() != if (pathName == null) emptySet() else setOf(pathName)) invalidMemory()
            val id = pathName?.let { name -> paths.getAll(name)?.let { if (it.size != 1) invalidMemory(); uuid(it.single()) } ?: invalidMemory() }
            val length = header(HttpHeaders.ContentLength)?.let {
                if (!it.matches(Regex("[0-9]{1,20}"))) invalidMemory()
                it.toLongOrNull()?.takeIf { n -> n <= AccountMemoryHttpConfiguration.MAX_REQUEST_BYTES } ?: invalidMemory()
            }
            val hasBody = operation in memoryBodyOperations
            val transfer = header(HttpHeaders.TransferEncoding)
            if (transfer != null && (!transfer.equals("chunked", true) || length != null || !hasBody)) invalidMemory()
            header(HttpHeaders.ContentEncoding)?.let { if (!it.equals("identity", true)) invalidMemory() }
            val media = header(HttpHeaders.ContentType)
            if (hasBody) {
                if (media == null || !media.matches(Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)))
                    throw AccountMemoryHttpFailure(400, "UNSUPPORTED_MEDIA")
            } else if (media != null || length != null && length != 0L) invalidMemory()
            return AccountMemoryHttpInput(operation, SecretText(token), device, key, id, ifMatch, cursor, limit, length, media)
        }
        private fun uuid(value: String): UUID { if (!CanonicalFormats.accepts("uuid", value)) invalidMemory(); return UUID.fromString(value) }
    }
}

internal suspend fun ApplicationCall.accountMemoryOperation(operation: String, configuration: AccountMemoryHttpConfiguration,
    validator: ContractBodyValidator) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    try {
        val input = AccountMemoryHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        val subject = configuration.subject(input.token)
        val reply = runInterruptible(configuration.databaseDispatcher) {
            val feedback = configuration.feedback; val memories = configuration.memories
            when (operation) {
                "createFeedback" -> memoryReply(feedback.createFeedback(subject, input.device, input.key!!, body!!))
                "updateFeedback" -> memoryReply(feedback.updateFeedback(subject, input.device, input.key!!, input.id!!, input.ifMatch!!, body!!))
                "deleteFeedback" -> memoryReply(feedback.deleteFeedback(subject, input.device, input.key!!, input.id!!, input.ifMatch!!))
                "listMemories" -> memories.listMemories(subject, input.device, input.cursor, input.limit)
                "getMemory" -> memories.getMemory(subject, input.device, input.id!!)
                "updateMemory" -> memoryReply(memories.updateMemory(subject, input.device, input.key!!, input.id!!, input.ifMatch!!, body!!))
                "deleteMemory" -> memoryReply(memories.deleteMemory(subject, input.device, input.key!!, input.id!!, input.ifMatch!!))
                else -> error("Unsupported account memory operation")
            }
        }
        currentCoroutineContext().ensureActive()
        val max = if (operation.endsWith("Feedback")) configuration.feedback.policy.maxResponseBytes else configuration.memories.policy.maxResponseBytes
        val text = validateAccountMemoryReply(operation, reply, validator, max)
        reply.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        if (reply.status == 204) respond(HttpStatusCode.NoContent) else respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
    } catch (f: CancellationException) { throw f }
    catch (f: AccountMemoryHttpFailure) { accountMemoryProblem(validator, operation, f.status, f.code) }
    catch (f: FeedbackFailure) { accountMemoryProblem(validator, operation, failureStatus(f.code.name), f.code.name) }
    catch (f: MemoryFailure) { accountMemoryProblem(validator, operation, failureStatus(f.code.name), f.code.name) }
    catch (_: CommitOutcomeUnknown) { accountMemoryProblem(validator, operation, 503, "OUTCOME_UNKNOWN") }
}
internal fun validateAccountMemoryReply(operation: String, reply: StoredReply, validator: ContractBodyValidator, maxBytes: Int): String {
    check(operation in accountMemoryOperations && maxBytes in 1..262_144)
    check(reply.status == when (operation) { "createFeedback" -> 201; "deleteFeedback", "deleteMemory" -> 204; else -> 200 })
    if (reply.status == 204) { check(reply.body == null && reply.etag == null); return "" }
    val text = checkNotNull(reply.body).toString(); val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    check(bytes.size <= maxBytes && validator.validateResponse(operation, reply.status, bytes, "application/json") == BodyValidationResult.Valid)
    if (operation == "listMemories") check(reply.etag == null)
    else {
        val etag = checkNotNull(reply.etag)
        check(etag.matches(Regex("\"[1-9][0-9]{0,18}\"")) && etag.removeSurrounding("\"").toLongOrNull() != null)
        check(BigDecimal(etag.removeSurrounding("\"")).compareTo(BigDecimal(reply.body.jsonObject.getValue("version").jsonPrimitive.content)) == 0)
    }
    return text
}
private suspend fun AccountMemoryHttpConfiguration.subject(token: SecretText): VerifiedSupabaseSubject {
    val result = try { verifier.verify(token) } catch (f: CancellationException) { throw f }
        catch (_: Exception) { throw AccountMemoryHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
    currentCoroutineContext().ensureActive()
    return when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> if (result.reason in setOf(FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION))
            unauthenticatedMemory() else throw AccountMemoryHttpFailure(503, "AUTHENTICATION_UNAVAILABLE")
    }
}
private fun memoryReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw AccountMemoryHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw AccountMemoryHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw AccountMemoryHttpFailure(409, "COMMAND_INCOMPLETE")
}
private fun failureStatus(code: String): Int = when (code) {
    "INPUT_INVALID", "CURSOR_INVALID" -> 422
    "UNAUTHENTICATED" -> 401
    "FORBIDDEN" -> 403
    "FEEDBACK_UNAVAILABLE", "MEMORY_UNAVAILABLE", "TARGET_UNAVAILABLE", "CONTEXT_UNAVAILABLE", "SOURCE_UNAVAILABLE", "RECIPE_RECALLED" -> 404
    "VERSION_CONFLICT", "FEEDBACK_CONFLICT", "PROJECTION_PENDING" -> 409
    "CURSOR_EXPIRED" -> 410
    else -> 503
}
private suspend fun ApplicationCall.accountMemoryProblem(validator: ContractBodyValidator, operation: String, status: Int, code: String) {
    currentCoroutineContext().ensureActive()
    problem(validator, HttpStatusCode.fromValue(status), code, "Account memory operation unavailable", operationId = operation)
}
private class AccountMemoryHttpFailure(val status: Int, val code: String) : RuntimeException("Account memory request unavailable")
private fun invalidMemory(): Nothing = throw AccountMemoryHttpFailure(400, "INPUT_INVALID")
private fun unauthenticatedMemory(): Nothing = throw AccountMemoryHttpFailure(401, "UNAUTHENTICATED")
