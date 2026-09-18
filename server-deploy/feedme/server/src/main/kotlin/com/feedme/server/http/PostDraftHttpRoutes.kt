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
import com.feedme.server.social.VerifiedSocialAccount
import com.feedme.server.social.drafts.PostDraftFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*

internal val postDraftHttpOperations = setOf("createPostDraft", "getPostDraft", "listPostDrafts", "updatePostDraft", "deletePostDraft")
private val readOperations = setOf("getPostDraft", "listPostDrafts")
private val bodyOperations = setOf("createPostDraft", "updatePostDraft")
private val versionOperations = setOf("updatePostDraft", "deletePostDraft")

/** Bounded untrusted input. No URI, caption, credential or owner assertion in diagnostics. */
internal class PostDraftHttpInput private constructor(
    val operation: String, val bearer: SocialHttpBearer, val key: UUID?, val draftId: UUID?,
    val ifMatch: String?, val cursor: String?, val limit: Int, val contentLength: Long?, val mediaType: String?,
) {
    val hasBody: Boolean get() = operation in bodyOperations
    override fun toString() = "PostDraftHttpInput(<redacted>)"

    suspend fun readBody(channel: ByteReadChannel, validator: ContractBodyValidator): JsonObject? {
        val exact = readBoundedHttpBody(channel, if (hasBody) PostDraftHttpConfiguration.MAX_REQUEST_BYTES else 0, contentLength, ::invalidDraft)
        return try { body(exact, validator) } finally { exact.fill(0) }
    }

    fun body(bytes: ByteArray, validator: ContractBodyValidator): JsonObject? {
        if (!hasBody) { if (bytes.isNotEmpty()) invalidDraft(); return null }
        if (bytes.isEmpty() || bytes.size > PostDraftHttpConfiguration.MAX_REQUEST_BYTES) invalidDraft()
        val document = try { WireDocument.decode(bytes, WireLimits(PostDraftHttpConfiguration.MAX_REQUEST_BYTES, 32)) }
            catch (_: WireDecodingException) { invalidDraft() }
        if (validator.validateRequest(operation, bytes, mediaType) != BodyValidationResult.Valid)
            throw PostDraftHttpFailure(422, "INPUT_INVALID")
        // Validate original bytes before projection; duplicate fields or number lexemes cannot
        // be silently normalized into a different original durable command.
        return Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
    }

    companion object {
        fun parse(operation: String, headers: Headers, query: Parameters, paths: Parameters): PostDraftHttpInput {
            if (operation !in postDraftHttpOperations) invalidDraft()
            fun header(name: String): String? = headers.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidDraft()
                it.single()
            }
            val authorization = header(HttpHeaders.Authorization) ?: throw PostDraftHttpFailure(401, "UNAUTHENTICATED")
            if (authorization.contains(',')) invalidDraft()
            if (authorization.length > 16_391) throw PostDraftHttpFailure(401, "UNAUTHENTICATED")
            val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE)
                .matchEntire(authorization)?.groupValues?.get(1) ?: throw PostDraftHttpFailure(401, "UNAUTHENTICATED")
            if (token.length !in 1..16_384 || !Regex("[A-Za-z0-9._~+/-]+=*").matches(token))
                throw PostDraftHttpFailure(401, "UNAUTHENTICATED")
            val device = header("X-Device-Session")?.let(::uuid) ?: throw PostDraftHttpFailure(401, "UNAUTHENTICATED")
            val keyText = header("Idempotency-Key")
            val key = if (operation !in readOperations) keyText?.let(::uuid) ?: invalidDraft()
                else { if (keyText != null) invalidDraft(); null }
            val ifMatch = header(HttpHeaders.IfMatch)
            if (operation in versionOperations) {
                if (ifMatch == null) throw PostDraftHttpFailure(428, "PRECONDITION_REQUIRED")
                if (!Regex("\"[0-9]{1,64}\"").matches(ifMatch)) invalidDraft()
            } else if (ifMatch != null) invalidDraft()
            if (header(HttpHeaders.IfNoneMatch) != null) invalidDraft()
            val requiresId = operation !in setOf("createPostDraft", "listPostDrafts")
            // Ktor includes decoded query parameters in ApplicationCall.parameters. Validate
            // their names/values independently below; they never become a draft identity.
            if (paths.names() - query.names() != if (requiresId) setOf("draftId") else emptySet()) invalidDraft()
            val id = if (requiresId) paths.getAll("draftId")?.let {
                if (it.size != 1) invalidDraft(); uuid(it.single())
            } ?: invalidDraft() else null
            if (operation == "listPostDrafts") {
                if (!setOf("cursor", "limit").containsAll(query.names())) invalidDraft()
            } else if (!query.isEmpty()) invalidDraft()
            fun queryValue(name: String): String? = query.getAll(name)?.let {
                if (it.size != 1) invalidDraft(); it.single()
            }
            val cursor = queryValue("cursor")?.also {
                if (it.isEmpty() || it.length > 2048 || it.any(Char::isISOControl) || it.any { char -> char.isSurrogate() }) invalidDraft()
            }
            val limit = queryValue("limit")?.let {
                if (!Regex("[0-9]{1,2}").matches(it)) invalidDraft()
                it.toInt().takeIf { n -> n in 1..50 } ?: invalidDraft()
            } ?: 20
            val length = header(HttpHeaders.ContentLength)?.let {
                if (!Regex("[0-9]{1,20}").matches(it)) invalidDraft()
                it.toLongOrNull()?.takeIf { n -> n <= PostDraftHttpConfiguration.MAX_REQUEST_BYTES } ?: invalidDraft()
            }
            val hasBody = operation in bodyOperations
            val transfer = header(HttpHeaders.TransferEncoding)
            if (transfer != null && (transfer.lowercase() != "chunked" || length != null || !hasBody)) invalidDraft()
            val encoding = header(HttpHeaders.ContentEncoding)
            if (encoding != null && encoding.lowercase() != "identity") invalidDraft()
            val media = header(HttpHeaders.ContentType)
            if (hasBody) {
                if (media == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(media))
                    throw PostDraftHttpFailure(400, "UNSUPPORTED_MEDIA")
            } else if (media != null || (length != null && length != 0L)) invalidDraft()
            return PostDraftHttpInput(operation, SocialHttpBearer(SecretText(token), device), key, id, ifMatch, cursor, limit, length, media)
        }
        private fun uuid(value: String): UUID {
            if (!CanonicalFormats.accepts("uuid", value)) invalidDraft()
            return UUID.fromString(value)
        }
    }
}

internal suspend fun PostDraftHttpConfiguration.authenticate(input: PostDraftHttpInput): VerifiedSocialAccount {
    currentCoroutineContext().ensureActive()
    val verified = try { verifier.verify(input.bearer) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw PostDraftHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
    currentCoroutineContext().ensureActive()
    val actor = when (verified) {
        is PortResult.Value -> verified.value
        is PortResult.Failure -> when (verified.reason) {
            FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION, FailureReason.INVALID_DATA,
            FailureReason.NOT_FOUND -> throw PostDraftHttpFailure(401, "UNAUTHENTICATED")
            FailureReason.FORBIDDEN -> throw PostDraftHttpFailure(403, "FORBIDDEN")
            FailureReason.RATE_LIMITED -> throw PostDraftHttpFailure(429, "RATE_LIMITED", verified.retryAfterSeconds)
            else -> throw PostDraftHttpFailure(503, "AUTHENTICATION_UNAVAILABLE", verified.retryAfterSeconds)
        }
    }
    if (actor.environment != environment || actor.deviceSessionId != input.bearer.deviceSessionId)
        throw PostDraftHttpFailure(401, "UNAUTHENTICATED")
    return actor
}

internal suspend fun ApplicationCall.postDraftOperation(operation: String, configuration: PostDraftHttpConfiguration,
    validator: ContractBodyValidator) {
    try {
        currentCoroutineContext().ensureActive()
        val input = PostDraftHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        val actor = configuration.authenticate(input)
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        val store = configuration.store
        val reply = runInterruptible(configuration.databaseDispatcher) {
            when (operation) {
                "createPostDraft" -> draftReply(store.createPostDraft(actor, input.key!!, body!!))
                "getPostDraft" -> store.getPostDraft(actor, input.draftId!!)
                "listPostDrafts" -> store.listPostDrafts(actor, input.cursor, input.limit)
                "updatePostDraft" -> draftReply(store.updatePostDraft(actor, input.key!!, input.draftId!!, input.ifMatch!!, body!!))
                "deletePostDraft" -> draftReply(store.deletePostDraft(actor, input.key!!, input.draftId!!, input.ifMatch!!))
                else -> error("Unsupported post draft operation")
            }
        }
        currentCoroutineContext().ensureActive()
        val text = validatePostDraftReply(operation, reply, validator, store.policy.maxResponseBytes)
        reply.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        if (reply.status == 204) respond(HttpStatusCode.NoContent)
        else respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: PostDraftHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Post draft request unavailable",
            operationId = operation, retryAfterSeconds = failure.retryAfterSeconds)
    } catch (failure: PostDraftFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Post draft request unavailable", operationId = operation)
    } catch (_: CommitOutcomeUnknown) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN", "Post draft outcome requires original-command reconciliation", operationId = operation)
    }
}

internal fun validatePostDraftReply(operation: String, reply: StoredReply, validator: ContractBodyValidator, maxBytes: Int): String {
    check(operation in postDraftHttpOperations && maxBytes in 1..262_144)
    check(reply.status == when (operation) { "createPostDraft" -> 201; "deletePostDraft" -> 204; else -> 200 })
    if (operation == "deletePostDraft") { check(reply.body == null && reply.etag == null); return "" }
    val text = reply.body?.toString() ?: error("Missing post draft response")
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    check(bytes.size <= maxBytes && validator.validateResponse(operation, reply.status, bytes, "application/json") == BodyValidationResult.Valid)
    val etag = reply.etag ?: error("Missing post draft version")
    check(Regex("\"[0-9]+\"").matches(etag) && etag.length <= 256)
    // List ETag is the owner's list head, not an individual draft version. Its exact scope and
    // freshness are established inside the store transaction and signed cursor.
    if (operation != "listPostDrafts") {
        val body = reply.body.jsonObject
        check(BigDecimal(etag.substring(1, etag.lastIndex)).compareTo(BigDecimal(body.getValue("version").jsonPrimitive.content)) == 0)
        check(body.getValue("status").jsonPrimitive.content in
            if (operation == "getPostDraft") setOf("draft", "published") else setOf("draft"))
    }
    return text
}

internal fun draftReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw PostDraftHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw PostDraftHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw PostDraftHttpFailure(409, "COMMAND_INCOMPLETE")
}
internal class PostDraftHttpFailure(val status: Int, val code: String, val retryAfterSeconds: Long? = null) :
    RuntimeException("Post draft HTTP request unavailable")
private fun invalidDraft(): Nothing = throw PostDraftHttpFailure(400, "INVALID_REQUEST")
