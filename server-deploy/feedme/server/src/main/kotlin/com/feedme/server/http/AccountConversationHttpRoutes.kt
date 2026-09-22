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
import com.feedme.server.social.conversations.ConversationFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal val accountConversationHttpOperations = setOf("getPrivacySettings", "updatePrivacySettings", "listThreads", "createThread", "getThread", "listMessages", "sendMessage", "markThreadRead")
private val conversationWrites = setOf("updatePrivacySettings", "createThread", "sendMessage", "markThreadRead")
private val conversationPages = setOf("listThreads", "listMessages")
private val threadPaths = setOf("getThread", "listMessages", "sendMessage", "markThreadRead")

internal class AccountConversationHttpInput private constructor(val operation: String, val token: SecretText, val device: UUID,
    val key: UUID?, val thread: UUID?, val ifMatch: String?, val cursor: String?, val limit: Int,
    private val contentLength: Long?, private val mediaType: String?) {
    override fun toString() = "AccountConversationHttpInput(<redacted>)"
    suspend fun readBody(channel: ByteReadChannel, validator: ContractBodyValidator): JsonObject? {
        val hasBody = operation in conversationWrites
        val bytes = readBoundedHttpBody(channel, if (hasBody) AccountConversationHttpConfiguration.MAX_REQUEST_BYTES else 0, contentLength, ::invalidConversation)
        return try {
            if (!hasBody) { if (bytes.isNotEmpty()) invalidConversation(); null }
            else {
                if (bytes.isEmpty()) invalidConversation()
                val document = try { WireDocument.decode(bytes, WireLimits(AccountConversationHttpConfiguration.MAX_REQUEST_BYTES, 12)) }
                    catch (_: WireDecodingException) { invalidConversation() }
                if (validator.validateRequest(operation, bytes, mediaType) != BodyValidationResult.Valid) throw ConversationHttpFailure(422, "INPUT_INVALID")
                Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
            }
        } finally { bytes.fill(0) }
    }
    companion object {
        fun parse(operation: String, headers: Headers, query: Parameters, paths: Parameters): AccountConversationHttpInput {
            if (operation !in accountConversationHttpOperations) invalidConversation()
            fun header(name: String): String? = headers.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidConversation(); it.single()
            }
            val authorization = header(HttpHeaders.Authorization)
            val device = header("X-Device-Session")
            if (authorization == null || authorization.length > 16391 || device == null) throw ConversationHttpFailure(401, "UNAUTHENTICATED")
            val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE).matchEntire(authorization)?.groupValues?.get(1)
                ?: throw ConversationHttpFailure(401, "UNAUTHENTICATED")
            if (token.length !in 1..16384 || !token.matches(Regex("[A-Za-z0-9._~+/-]+=*"))) throw ConversationHttpFailure(401, "UNAUTHENTICATED")
            val hasBody = operation in conversationWrites
            val keyText = header("Idempotency-Key")
            val key = if (hasBody) keyText?.let(::uuid) ?: invalidConversation() else { if (keyText != null) invalidConversation(); null }
            val ifMatch = header(HttpHeaders.IfMatch)
            if (operation == "updatePrivacySettings") {
                if (ifMatch == null) throw ConversationHttpFailure(428, "PRECONDITION_REQUIRED")
                if (!ifMatch.matches(Regex("\"[1-9][0-9]{0,18}\""))) invalidConversation()
            } else if (ifMatch != null) invalidConversation()
            if (header(HttpHeaders.IfNoneMatch) != null) invalidConversation()
            val allowedQuery = if (operation in conversationPages) setOf("cursor", "limit") else emptySet()
            if (!allowedQuery.containsAll(query.names())) invalidConversation()
            fun parameter(name: String): String? = query.getAll(name)?.let { if (it.size != 1 || it.single().any(Char::isISOControl)) invalidConversation(); it.single() }
            val cursor = parameter("cursor")?.also { if (it.length !in 1..2048) invalidConversation() }
            val limit = parameter("limit")?.let { value ->
                if (!value.matches(Regex("[1-9][0-9]?"))) invalidConversation()
                value.toInt().also { if (it !in 1..50) invalidConversation() }
            } ?: 20
            val allowedPaths = (if (operation in threadPaths) setOf("threadId") else emptySet()) + query.names()
            if (!allowedPaths.containsAll(paths.names())) invalidConversation()
            val thread = if (operation in threadPaths) paths.getAll("threadId")?.let {
                if (it.size != 1) invalidConversation(); uuid(it.single())
            } ?: invalidConversation() else null
            val length = header(HttpHeaders.ContentLength)?.let {
                if (!it.matches(Regex("[0-9]{1,20}"))) invalidConversation()
                it.toLongOrNull()?.takeIf { size -> size <= AccountConversationHttpConfiguration.MAX_REQUEST_BYTES } ?: invalidConversation()
            }
            val transfer = header(HttpHeaders.TransferEncoding)
            if (transfer != null && (transfer.lowercase() != "chunked" || length != null || !hasBody)) invalidConversation()
            if (header(HttpHeaders.ContentEncoding)?.lowercase()?.let { it != "identity" } == true) invalidConversation()
            val media = header(HttpHeaders.ContentType)
            if (hasBody) {
                if (media == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(media)) throw ConversationHttpFailure(400, "UNSUPPORTED_MEDIA")
            } else if (media != null || (length != null && length != 0L)) invalidConversation()
            return AccountConversationHttpInput(operation, SecretText(token), uuid(device), key, thread, ifMatch, cursor, limit, length, media)
        }
        private fun uuid(value: String): UUID { if (!CanonicalFormats.accepts("uuid", value)) invalidConversation(); return UUID.fromString(value) }
    }
}

internal suspend fun ApplicationCall.accountConversationOperation(operation: String, configuration: AccountConversationHttpConfiguration, validator: ContractBodyValidator) {
    try {
        currentCoroutineContext().ensureActive()
        val input = AccountConversationHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        val verified = try { configuration.verifier.verify(input.token) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw ConversationHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
        currentCoroutineContext().ensureActive()
        val subject = when (verified) {
            is PortResult.Value -> verified.value
            is PortResult.Failure -> throw ConversationHttpFailure(if (verified.reason in setOf(FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION)) 401 else 503,
                if (verified.reason in setOf(FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION)) "UNAUTHENTICATED" else "AUTHENTICATION_UNAVAILABLE")
        }
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        val reply = runInterruptible(configuration.databaseDispatcher) {
            val store = configuration.store
            when (operation) {
                "getPrivacySettings" -> store.getPrivacySettings(subject, input.device)
                "updatePrivacySettings" -> conversationReply(store.updatePrivacySettings(subject, input.device, input.key!!, input.ifMatch!!, body!!))
                "listThreads" -> store.listThreads(subject, input.device, input.cursor, input.limit)
                "createThread" -> conversationReply(store.createThread(subject, input.device, input.key!!, body!!))
                "getThread" -> store.getThread(subject, input.device, input.thread!!)
                "listMessages" -> store.listMessages(subject, input.device, input.thread!!, input.cursor, input.limit)
                "sendMessage" -> conversationReply(store.sendMessage(subject, input.device, input.key!!, input.thread!!, body!!))
                "markThreadRead" -> conversationReply(store.markThreadRead(subject, input.device, input.key!!, input.thread!!, body!!))
                else -> error("Unsupported conversation operation")
            }
        }
        currentCoroutineContext().ensureActive()
        val text = validateAccountConversationReply(operation, reply, validator, configuration.store.policy.maxResponseBytes)
        reply.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: ConversationHttpFailure) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Conversation request unavailable", operationId = operation) }
    catch (failure: ConversationFailure) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Conversation request unavailable", operationId = operation) }
    catch (_: CommitOutcomeUnknown) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN", "Conversation outcome requires reconciliation", operationId = operation) }
}
internal fun validateAccountConversationReply(operation: String, reply: StoredReply, validator: ContractBodyValidator, maximumBytes: Int): String {
    check(operation in accountConversationHttpOperations && maximumBytes in 1..262144)
    check(reply.status == if (operation == "createThread") 201 else 200)
    val body = checkNotNull(reply.body).jsonObject; val text = body.toString(); val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    check(bytes.size <= maximumBytes && validator.validateResponse(operation, reply.status, bytes, "application/json") == BodyValidationResult.Valid)
    if (operation in conversationPages) check(reply.etag == null)
    else {
        val etag = checkNotNull(reply.etag); check(etag.matches(Regex("\"[1-9][0-9]{0,18}\"")))
        check(BigDecimal(etag.drop(1).dropLast(1)).compareTo(BigDecimal(body.getValue("version").jsonPrimitive.content)) == 0)
    }
    return text
}
private fun conversationReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw ConversationHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw ConversationHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw ConversationHttpFailure(409, "COMMAND_INCOMPLETE")
}
internal class ConversationHttpFailure(val status: Int, val code: String) : RuntimeException("Conversation HTTP request unavailable")
private fun invalidConversation(): Nothing = throw ConversationHttpFailure(400, "INVALID_REQUEST")
