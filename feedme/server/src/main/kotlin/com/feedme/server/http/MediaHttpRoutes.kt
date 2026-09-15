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
import com.feedme.server.media.MediaFailure
import com.feedme.server.media.VerifiedMediaAccount
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*

internal val mediaHttpOperations = setOf("prepareMediaUpload", "completeMediaUpload", "getMediaStatus", "deleteDraftMedia")
private val uploadCapabilityFields = setOf("uploadUrl", "uploadMethod", "uploadFields", "uploadExpiresAt")

internal class MediaHttpInput private constructor(
    val operation: String, val bearer: MediaHttpBearer, val key: UUID?, val mediaId: UUID?,
    val ifMatch: String?, val contentLength: Long?, val mediaType: String?,
) {
    val hasBody: Boolean get() = operation in setOf("prepareMediaUpload", "completeMediaUpload")
    override fun toString() = "MediaHttpInput(<redacted>)"

    suspend fun readBody(channel: ByteReadChannel, validator: ContractBodyValidator): JsonObject? {
        val buffer = ByteArray(if (hasBody) MediaHttpConfiguration.MAX_REQUEST_BYTES + 1 else 1)
        var size = 0
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = channel.readAvailable(buffer, size, buffer.size - size)
                if (read == -1) { channel.closedCause?.let { throw it }; break }
                size += read
                if (size == buffer.size) invalidMedia()
            }
            currentCoroutineContext().ensureActive()
            if (contentLength != null && contentLength != size.toLong()) invalidMedia()
            return body(buffer.copyOf(size), validator)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { currentCoroutineContext().ensureActive(); invalidMedia() }
        finally { buffer.fill(0) }
    }

    fun body(bytes: ByteArray, validator: ContractBodyValidator): JsonObject? {
        if (!hasBody) { if (bytes.isNotEmpty()) invalidMedia(); return null }
        if (bytes.isEmpty() || bytes.size > MediaHttpConfiguration.MAX_REQUEST_BYTES) invalidMedia()
        val document = try { WireDocument.decode(bytes, WireLimits(MediaHttpConfiguration.MAX_REQUEST_BYTES, 32)) }
            catch (_: WireDecodingException) { invalidMedia() }
        if (validator.validateRequest(operation, bytes, mediaType) != BodyValidationResult.Valid)
            throw MediaHttpFailure(422, "INPUT_INVALID")
        // Only project after validating original bytes: duplicate members, Unicode and integer
        // spelling must not be normalized away by permissive JSON/Double parsing.
        return Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
    }

    companion object {
        fun parse(operation: String, headers: Headers, query: Parameters, paths: Parameters): MediaHttpInput {
            if (operation !in mediaHttpOperations || !query.isEmpty()) invalidMedia()
            fun header(name: String): String? = headers.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidMedia()
                it.single()
            }
            val authorization = header(HttpHeaders.Authorization) ?: throw MediaHttpFailure(401, "UNAUTHENTICATED")
            if (authorization.contains(',')) invalidMedia()
            if (authorization.length > 16_391) throw MediaHttpFailure(401, "UNAUTHENTICATED")
            val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE)
                .matchEntire(authorization)?.groupValues?.get(1) ?: throw MediaHttpFailure(401, "UNAUTHENTICATED")
            if (token.length !in 1..16_384 || !Regex("[A-Za-z0-9._~+/-]+=*").matches(token))
                throw MediaHttpFailure(401, "UNAUTHENTICATED")
            val device = header("X-Device-Session")?.let(::uuid) ?: throw MediaHttpFailure(401, "UNAUTHENTICATED")
            val hasBody = operation in setOf("prepareMediaUpload", "completeMediaUpload")
            val mutation = operation != "getMediaStatus"
            val keyText = header("Idempotency-Key")
            val key = if (mutation) keyText?.let(::uuid) ?: invalidMedia()
                else { if (keyText != null) invalidMedia(); null }
            val ifMatch = header(HttpHeaders.IfMatch)
            if (operation == "deleteDraftMedia") {
                if (ifMatch == null) throw MediaHttpFailure(428, "PRECONDITION_REQUIRED")
                if (!Regex("\"[0-9]{1,64}\"").matches(ifMatch)) invalidMedia()
            } else if (ifMatch != null) invalidMedia()
            if (header(HttpHeaders.IfNoneMatch) != null) invalidMedia()
            val requiresId = operation != "prepareMediaUpload"
            if (paths.names() != if (requiresId) setOf("mediaId") else emptySet()) invalidMedia()
            val mediaId = if (requiresId) paths.getAll("mediaId")?.let {
                if (it.size != 1) invalidMedia()
                uuid(it.single())
            } ?: invalidMedia() else null
            val length = header(HttpHeaders.ContentLength)?.let {
                if (!Regex("[0-9]{1,20}").matches(it)) invalidMedia()
                it.toLongOrNull()?.takeIf { n -> n <= MediaHttpConfiguration.MAX_REQUEST_BYTES } ?: invalidMedia()
            }
            val transfer = header(HttpHeaders.TransferEncoding)
            if (transfer != null && (transfer.lowercase() != "chunked" || length != null || !hasBody)) invalidMedia()
            val encoding = header(HttpHeaders.ContentEncoding)
            if (encoding != null && encoding.lowercase() != "identity") invalidMedia()
            val media = header(HttpHeaders.ContentType)
            if (hasBody) {
                if (media == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(media))
                    throw MediaHttpFailure(400, "UNSUPPORTED_MEDIA")
            } else if (media != null || (length != null && length != 0L)) invalidMedia()
            return MediaHttpInput(operation, MediaHttpBearer(SecretText(token), device), key, mediaId, ifMatch, length, media)
        }
        private fun uuid(text: String): UUID {
            if (!CanonicalFormats.accepts("uuid", text)) invalidMedia()
            return UUID.fromString(text)
        }
    }
}

internal suspend fun MediaHttpConfiguration.authenticate(input: MediaHttpInput): VerifiedMediaAccount {
    currentCoroutineContext().ensureActive()
    val verified = try { verifier.verify(input.bearer) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw MediaHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
    currentCoroutineContext().ensureActive()
    val actor = when (verified) {
        is PortResult.Value -> verified.value
        is PortResult.Failure -> when (verified.reason) {
            FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION, FailureReason.INVALID_DATA,
            FailureReason.NOT_FOUND -> throw MediaHttpFailure(401, "UNAUTHENTICATED")
            FailureReason.FORBIDDEN -> throw MediaHttpFailure(403, "FORBIDDEN")
            FailureReason.RATE_LIMITED -> throw MediaHttpFailure(429, "RATE_LIMITED", verified.retryAfterSeconds)
            else -> throw MediaHttpFailure(503, "AUTHENTICATION_UNAVAILABLE", verified.retryAfterSeconds)
        }
    }
    if (actor.environment != environment || actor.deviceSessionId != input.bearer.deviceSessionId)
        throw MediaHttpFailure(401, "UNAUTHENTICATED")
    return actor
}

internal suspend fun ApplicationCall.mediaOperation(operation: String, configuration: MediaHttpConfiguration,
    validator: ContractBodyValidator) {
    try {
        currentCoroutineContext().ensureActive()
        val input = MediaHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        val actor = configuration.authenticate(input)
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        val store = configuration.store
        val reply = runInterruptible(configuration.databaseDispatcher) {
            when (operation) {
                "prepareMediaUpload" -> mediaReply(store.prepareMediaUpload(actor, input.key!!, body!!))
                "completeMediaUpload" -> mediaReply(store.completeMediaUpload(actor, input.key!!, input.mediaId!!, body!!))
                "getMediaStatus" -> store.getMediaStatus(actor, input.mediaId!!)
                "deleteDraftMedia" -> mediaReply(store.deleteDraftMedia(actor, input.key!!, input.mediaId!!, input.ifMatch!!))
                else -> error("Unsupported media operation")
            }
        }
        currentCoroutineContext().ensureActive()
        val text = validateMediaReply(operation, reply, validator, store.policy.maxResponseBytes)
        reply.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        if (reply.status == 204) respond(HttpStatusCode.NoContent)
        else respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: MediaHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Media request unavailable",
            operationId = operation, retryAfterSeconds = failure.retryAfterSeconds)
    } catch (failure: MediaFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Media request unavailable", operationId = operation)
    } catch (_: CommitOutcomeUnknown) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN", "Media outcome requires original-command reconciliation", operationId = operation)
    }
}

internal fun validateMediaReply(operation: String, reply: StoredReply, validator: ContractBodyValidator, maxBytes: Int): String {
    check(operation in mediaHttpOperations && maxBytes in 1..262_144)
    check(reply.status == when (operation) { "prepareMediaUpload" -> 201; "deleteDraftMedia" -> 204; else -> 200 })
    if (operation == "deleteDraftMedia") { check(reply.body == null && reply.etag == null); return "" }
    val text = reply.body?.toString() ?: error("Missing media response")
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    check(bytes.size <= maxBytes && validator.validateResponse(operation, reply.status, bytes, "application/json") == BodyValidationResult.Valid)
    val body = reply.body.jsonObject
    if (operation == "completeMediaUpload") check(body.getValue("status").jsonPrimitive.content == "processing")
    val etag = reply.etag ?: error("Missing media version")
    check(Regex("\"[0-9]+\"").matches(etag) && etag.length <= 256)
    check(BigDecimal(etag.substring(1, etag.lastIndex)).compareTo(BigDecimal(body.getValue("version").jsonPrimitive.content)) == 0)
    val capability = body.keys.intersect(uploadCapabilityFields)
    if (operation != "prepareMediaUpload") check(capability.isEmpty())
    else {
        check(body.getValue("status").jsonPrimitive.content == "awaitingUpload" && capability == uploadCapabilityFields)
        val url = URI(body.getValue("uploadUrl").jsonPrimitive.content)
        check(url.scheme == "https" && !url.host.isNullOrBlank() && url.userInfo == null && url.fragment == null)
        check(body.getValue("uploadMethod").jsonPrimitive.content == "POST")
        // Fresh expiry and provider restriction are locked store/signer responsibilities. Do not
        // compare process wall clocks or claim this projection verifies a storage POST policy.
        Instant.parse(body.getValue("uploadExpiresAt").jsonPrimitive.content)
    }
    return text
}

private fun mediaReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw MediaHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw MediaHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw MediaHttpFailure(409, "COMMAND_INCOMPLETE")
}
internal class MediaHttpFailure(val status: Int, val code: String, val retryAfterSeconds: Long? = null) :
    RuntimeException("Media HTTP request unavailable")
private fun invalidMedia(): Nothing = throw MediaHttpFailure(400, "INVALID_REQUEST")
