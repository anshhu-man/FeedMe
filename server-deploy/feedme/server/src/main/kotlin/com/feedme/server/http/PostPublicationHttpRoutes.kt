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
import com.feedme.server.social.posts.PostPublicationFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*

/** Single purpose ingress. This cannot authorize a post read, a draft Save or media upload. */
internal class PostPublicationHttpInput private constructor(
    val bearer: SocialHttpBearer,
    val key: UUID,
    val contentLength: Long?,
    val mediaType: String,
) {
    override fun toString() = "PostPublicationHttpInput(<redacted>)"

    suspend fun readBody(channel: ByteReadChannel, validator: ContractBodyValidator): JsonObject {
        val exact = readBoundedHttpBody(channel, PostPublicationHttpConfiguration.MAX_REQUEST_BYTES, contentLength, ::invalidPublication)
        return try { body(exact, validator) } finally { exact.fill(0) }
    }

    fun body(bytes: ByteArray, validator: ContractBodyValidator): JsonObject {
        if (bytes.isEmpty() || bytes.size > PostPublicationHttpConfiguration.MAX_REQUEST_BYTES) invalidPublication()
        val document = try { WireDocument.decode(bytes, WireLimits(PostPublicationHttpConfiguration.MAX_REQUEST_BYTES, 32)) }
            catch (_: WireDecodingException) { invalidPublication() }
        if (validator.validateRequest("publishPost", bytes, mediaType) != BodyValidationResult.Valid)
            throw PostPublicationHttpFailure(422, "INPUT_INVALID")
        // Preserve exact JSON numbers and optional-field presence. In particular, do not turn
        // draftVersion into a Double or fill a missing half-pair. Both branches reach the store;
        // half-pair and existing-root checks are semantic transaction validation, not this schema.
        return Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
    }

    companion object {
        fun parse(headers: Headers, query: Parameters, paths: Parameters): PostPublicationHttpInput {
            if (!query.isEmpty() || !paths.isEmpty()) invalidPublication()
            fun header(name: String): String? = headers.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidPublication()
                it.single()
            }
            val authorization = header(HttpHeaders.Authorization) ?: throw PostPublicationHttpFailure(401, "UNAUTHENTICATED")
            if (authorization.contains(',')) invalidPublication()
            if (authorization.length > 16_391) throw PostPublicationHttpFailure(401, "UNAUTHENTICATED")
            val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE)
                .matchEntire(authorization)?.groupValues?.get(1) ?: throw PostPublicationHttpFailure(401, "UNAUTHENTICATED")
            if (token.length !in 1..16_384 || !Regex("[A-Za-z0-9._~+/-]+=*").matches(token))
                throw PostPublicationHttpFailure(401, "UNAUTHENTICATED")
            val device = header("X-Device-Session")?.let(::uuid) ?: throw PostPublicationHttpFailure(401, "UNAUTHENTICATED")
            val key = header("Idempotency-Key")?.let(::uuid) ?: invalidPublication()
            // publishPost uses the reviewed pair in its body, never a write precondition header.
            if (header(HttpHeaders.IfMatch) != null || header(HttpHeaders.IfNoneMatch) != null) invalidPublication()
            val length = header(HttpHeaders.ContentLength)?.let {
                if (!Regex("[0-9]{1,20}").matches(it)) invalidPublication()
                it.toLongOrNull()?.takeIf { n -> n <= PostPublicationHttpConfiguration.MAX_REQUEST_BYTES } ?: invalidPublication()
            }
            val transfer = header(HttpHeaders.TransferEncoding)
            if (transfer != null && (transfer.lowercase() != "chunked" || length != null)) invalidPublication()
            val encoding = header(HttpHeaders.ContentEncoding)
            if (encoding != null && encoding.lowercase() != "identity") invalidPublication()
            val media = header(HttpHeaders.ContentType)
            if (media == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(media))
                throw PostPublicationHttpFailure(400, "UNSUPPORTED_MEDIA")
            return PostPublicationHttpInput(SocialHttpBearer(SecretText(token), device), key, length, media)
        }
        private fun uuid(value: String): UUID {
            if (!CanonicalFormats.accepts("uuid", value)) invalidPublication()
            return UUID.fromString(value)
        }
    }
}

internal suspend fun authenticatePostPublication(input: PostPublicationHttpInput, environment: String,
    verifier: SocialHttpVerifier): VerifiedSocialAccount {
    currentCoroutineContext().ensureActive()
    val verified = try { verifier.verify(input.bearer) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw PostPublicationHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
    currentCoroutineContext().ensureActive()
    val actor = when (verified) {
        is PortResult.Value -> verified.value
        is PortResult.Failure -> when (verified.reason) {
            FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION, FailureReason.INVALID_DATA,
            FailureReason.NOT_FOUND -> throw PostPublicationHttpFailure(401, "UNAUTHENTICATED")
            FailureReason.FORBIDDEN -> throw PostPublicationHttpFailure(403, "FORBIDDEN")
            FailureReason.RATE_LIMITED -> throw PostPublicationHttpFailure(429, "RATE_LIMITED", verified.retryAfterSeconds)
            else -> throw PostPublicationHttpFailure(503, "AUTHENTICATION_UNAVAILABLE", verified.retryAfterSeconds)
        }
    }
    if (actor.environment != environment || actor.deviceSessionId != input.bearer.deviceSessionId)
        throw PostPublicationHttpFailure(401, "UNAUTHENTICATED")
    return actor
}

internal suspend fun ApplicationCall.postPublicationOperation(configuration: PostPublicationHttpConfiguration,
    validator: ContractBodyValidator) {
    try {
        currentCoroutineContext().ensureActive()
        val input = PostPublicationHttpInput.parse(request.headers, request.queryParameters, parameters)
        val actor = authenticatePostPublication(input, configuration.environment, configuration.verifier)
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        val reply = runInterruptible(configuration.databaseDispatcher) {
            publicationReply(configuration.store.publishPost(actor, input.key, body))
        }
        currentCoroutineContext().ensureActive()
        val text = validatePostPublicationReply(reply, validator, configuration.store.policy.maxResponseBytes, actor.accountId)
        response.headers.append(HttpHeaders.ETag, reply.etag!!)
        respondText(text, ContentType.Application.Json, HttpStatusCode.Created)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: PostPublicationHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Publication request unavailable",
            operationId = "publishPost", retryAfterSeconds = failure.retryAfterSeconds)
    } catch (failure: PostPublicationFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Publication request unavailable", operationId = "publishPost")
    } catch (_: CommitOutcomeUnknown) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN",
            "Publication outcome requires original-command reconciliation", operationId = "publishPost")
    }
}

/** Only the original publication receipt, not a later post projection or a draft response. */
internal fun validatePostPublicationReply(reply: StoredReply, validator: ContractBodyValidator, maxBytes: Int, ownerId: UUID): String {
    check(maxBytes in 1..262_144 && reply.status == 201)
    val text = reply.body?.toString() ?: error("Missing publication response")
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    check(bytes.size <= maxBytes && validator.validateResponse("publishPost", 201, bytes, "application/json") == BodyValidationResult.Valid)
    val etag = reply.etag ?: error("Missing publication version")
    check(Regex("\"[0-9]+\"").matches(etag) && etag.length <= 256)
    val body = reply.body.jsonObject
    check(BigDecimal(etag.substring(1, etag.lastIndex)).compareTo(BigDecimal(body.getValue("version").jsonPrimitive.content)) == 0)
    check(body.getValue("status").jsonPrimitive.content == "published")
    check(UUID.fromString(body.getValue("author").jsonObject.getValue("userId").jsonPrimitive.content) == ownerId)
    val published = Instant.parse(body.getValue("publishedAt").jsonPrimitive.content)
    check(Instant.parse(body.getValue("createdAt").jsonPrimitive.content) == published)
    check(Instant.parse(body.getValue("updatedAt").jsonPrimitive.content) == published)
    check(Duration.between(published, Instant.parse(body.getValue("expiresAt").jsonPrimitive.content)) == Duration.ofHours(24))
    return text
}

internal fun publicationReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw PostPublicationHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw PostPublicationHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw PostPublicationHttpFailure(409, "COMMAND_INCOMPLETE")
}
internal class PostPublicationHttpFailure(val status: Int, val code: String, val retryAfterSeconds: Long? = null) :
    RuntimeException("Publication HTTP request unavailable")
private fun invalidPublication(): Nothing = throw PostPublicationHttpFailure(400, "INVALID_REQUEST")
