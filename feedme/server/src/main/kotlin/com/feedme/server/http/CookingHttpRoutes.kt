package com.feedme.server.http

import com.feedme.contracts.CanonicalFormats
import com.feedme.contracts.WireDecodingException
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.cooking.CookingFailure
import com.feedme.server.cooking.VerifiedCookingPrincipal
import com.feedme.server.db.CommandActor
import com.feedme.server.db.CommandResult
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.StoredReply
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.IOException
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*

internal val cookingHttpOperations = setOf("createCookSession", "getCookSession",
    "updateCookSession", "completeCookSession")
private val cookingBodyOperations = cookingHttpOperations - "getCookSession"

internal class CookingHttpInput private constructor(
    val operation: String, val bearer: CookingHttpBearer, val key: UUID?,
    val sessionId: UUID?, val ifMatch: String?, val contentLength: Long?, val mediaType: String?,
) {
    val hasBody: Boolean get() = operation in cookingBodyOperations
    override fun toString() = "CookingHttpInput(<redacted>)"

    suspend fun readBody(channel: ByteReadChannel, validator: ContractBodyValidator): JsonObject? {
        val buffer = ByteArray(if (hasBody) CookingHttpConfiguration.MAX_REQUEST_BYTES + 1 else 1)
        var size = 0
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = channel.readAvailable(buffer, size, buffer.size - size)
                if (read == -1) { channel.closedCause?.let { throw it }; break }
                size += read
                if (size == buffer.size) invalidCooking()
            }
            currentCoroutineContext().ensureActive()
            if (contentLength != null && contentLength != size.toLong()) invalidCooking()
            return body(buffer.copyOf(size), validator)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { currentCoroutineContext().ensureActive(); invalidCooking() }
        finally { buffer.fill(0) }
    }

    fun body(bytes: ByteArray, validator: ContractBodyValidator): JsonObject? {
        if (!hasBody) { if (bytes.isNotEmpty()) invalidCooking(); return null }
        if (bytes.isEmpty() || bytes.size > CookingHttpConfiguration.MAX_REQUEST_BYTES) invalidCooking()
        val document = try { WireDocument.decode(bytes, WireLimits(CookingHttpConfiguration.MAX_REQUEST_BYTES, 32)) }
            catch (_: WireDecodingException) { invalidCooking() }
        if (validator.validateRequest(operation, bytes, mediaType) != BodyValidationResult.Valid)
            throw CookingHttpFailure(422, "INPUT_INVALID")
        // Validate original wire bytes first. Duplicate members, malformed Unicode, and exact
        // sequence/timer integer spelling must not be lost in a permissive JSON/Double projection.
        return Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
    }

    companion object {
        fun parse(operation: String, headers: Headers, query: Parameters, paths: Parameters): CookingHttpInput {
            if (operation !in cookingHttpOperations || query.names().isNotEmpty()) invalidCooking()
            fun header(name: String): String? = headers.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidCooking()
                it.single()
            }
            val authorization = header(HttpHeaders.Authorization) ?: throw CookingHttpFailure(401, "UNAUTHENTICATED")
            if (authorization.contains(',')) invalidCooking()
            if (authorization.length > 16_391) throw CookingHttpFailure(401, "UNAUTHENTICATED")
            val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE)
                .matchEntire(authorization)?.groupValues?.get(1) ?: throw CookingHttpFailure(401, "UNAUTHENTICATED")
            if (token.length !in 1..16_384 || !Regex("[A-Za-z0-9._~+/-]+=*").matches(token))
                throw CookingHttpFailure(401, "UNAUTHENTICATED")
            val bearer = CookingHttpBearer(SecretText(token), header("X-Device-Session")?.let(::uuid))
            val hasBody = operation in cookingBodyOperations
            val keyText = header("Idempotency-Key")
            val key = if (hasBody) keyText?.let(::uuid) ?: invalidCooking()
                else { if (keyText != null) invalidCooking(); null }
            val ifMatch = header(HttpHeaders.IfMatch)
            if (operation == "updateCookSession") {
                if (ifMatch == null) throw CookingHttpFailure(428, "PRECONDITION_REQUIRED")
                if (!Regex("\"[0-9]{1,64}\"").matches(ifMatch)) invalidCooking()
            } else if (ifMatch != null) invalidCooking()
            if (header(HttpHeaders.IfNoneMatch) != null) invalidCooking()
            val hasSession = operation != "createCookSession"
            if (paths.names() != if (hasSession) setOf("sessionId") else emptySet()) invalidCooking()
            val session = if (hasSession) paths.getAll("sessionId")?.let {
                if (it.size != 1) invalidCooking()
                uuid(it.single())
            } ?: invalidCooking() else null
            val length = header(HttpHeaders.ContentLength)?.let {
                if (!Regex("[0-9]{1,20}").matches(it)) invalidCooking()
                it.toLongOrNull()?.takeIf { n -> n <= CookingHttpConfiguration.MAX_REQUEST_BYTES } ?: invalidCooking()
            }
            val transfer = header(HttpHeaders.TransferEncoding)
            if (transfer != null && (transfer.lowercase() != "chunked" || length != null || !hasBody)) invalidCooking()
            val encoding = header(HttpHeaders.ContentEncoding)
            if (encoding != null && encoding.lowercase() != "identity") invalidCooking()
            val media = header(HttpHeaders.ContentType)
            if (hasBody) {
                if (media == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(media))
                    throw CookingHttpFailure(400, "UNSUPPORTED_MEDIA")
            } else if (media != null || (length != null && length != 0L)) invalidCooking()
            return CookingHttpInput(operation, bearer, key, session, ifMatch, length, media)
        }
        private fun uuid(value: String): UUID {
            if (!CanonicalFormats.accepts("uuid", value)) invalidCooking()
            return UUID.fromString(value)
        }
    }
}

internal suspend fun CookingHttpConfiguration.authenticate(input: CookingHttpInput): VerifiedCookingPrincipal {
    currentCoroutineContext().ensureActive()
    val result = try { verifier.verify(input.bearer) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw CookingHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
    currentCoroutineContext().ensureActive()
    val principal = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> when (result.reason) {
            FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION, FailureReason.INVALID_DATA,
            FailureReason.NOT_FOUND -> throw CookingHttpFailure(401, "UNAUTHENTICATED")
            FailureReason.FORBIDDEN -> throw CookingHttpFailure(403, "FORBIDDEN")
            FailureReason.RATE_LIMITED -> throw CookingHttpFailure(429, "RATE_LIMITED", result.retryAfterSeconds)
            else -> throw CookingHttpFailure(503, "AUTHENTICATION_UNAVAILABLE", result.retryAfterSeconds)
        }
    }
    if (principal.environment != environment || when (principal.kind) {
        CommandActor.ACCOUNT -> input.bearer.deviceSessionId == null ||
            principal.deviceSessionId != input.bearer.deviceSessionId || principal.guestSessionId != null
        CommandActor.GUEST -> input.bearer.deviceSessionId != null ||
            principal.deviceSessionId != null || principal.guestSessionId == null
        else -> true
    }) throw CookingHttpFailure(401, "UNAUTHENTICATED")
    return principal
}

internal suspend fun ApplicationCall.cookingOperation(operation: String, configuration: CookingHttpConfiguration,
    validator: ContractBodyValidator) {
    try {
        currentCoroutineContext().ensureActive()
        val input = CookingHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        val actor = configuration.authenticate(input)
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        val store = configuration.store
        // Interruption can occur after COMMIT. Preserve original key/body/sequence; never retry
        // automatically, add a completion If-Match, or claim rollback merely from a lost reply.
        val reply = runInterruptible(configuration.databaseDispatcher) {
            when (operation) {
                "getCookSession" -> store.getCookSession(actor, input.sessionId!!)
                "createCookSession" -> cookingReply(store.createCookSession(actor, input.key!!, body!!))
                "updateCookSession" -> cookingReply(store.updateCookSession(actor, input.key!!, input.sessionId!!, input.ifMatch!!, body!!))
                "completeCookSession" -> cookingReply(store.completeCookSession(actor, input.key!!, input.sessionId!!, body!!))
                else -> error("Unsupported cooking operation")
            }
        }
        currentCoroutineContext().ensureActive()
        val text = validateCookingReply(operation, reply, validator, store.policy.maxResponseBytes)
        response.headers.append(HttpHeaders.ETag, checkNotNull(reply.etag))
        respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: CookingHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Cooking request unavailable",
            operationId = operation, retryAfterSeconds = failure.retryAfterSeconds)
    } catch (failure: CookingFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name,
            "Cooking request unavailable", operationId = operation)
    } catch (_: CommitOutcomeUnknown) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN",
            "Cooking outcome requires reconciliation", operationId = operation)
    }
}

internal fun validateCookingReply(operation: String, reply: StoredReply, validator: ContractBodyValidator, maxBytes: Int): String {
    check(operation in cookingHttpOperations && maxBytes in 1..262_144)
    check(reply.status == if (operation == "createCookSession") 201 else 200)
    val text = reply.body?.toString() ?: error("Missing cooking response")
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    check(bytes.size <= maxBytes && validator.validateResponse(operation, reply.status, bytes, "application/json") == BodyValidationResult.Valid)
    val etag = reply.etag ?: error("Missing cooking version")
    check(Regex("\"[0-9]+\"").matches(etag) && etag.length <= 256)
    check(BigDecimal(etag.substring(1, etag.lastIndex)).compareTo(BigDecimal(reply.body.jsonObject.getValue("version").jsonPrimitive.content)) == 0)
    return text
}

private fun cookingReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw CookingHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw CookingHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw CookingHttpFailure(409, "COMMAND_INCOMPLETE")
}
internal class CookingHttpFailure(val status: Int, val code: String, val retryAfterSeconds: Long? = null) :
    RuntimeException("Cooking HTTP request unavailable")
private fun invalidCooking(): Nothing = throw CookingHttpFailure(400, "INVALID_REQUEST")
