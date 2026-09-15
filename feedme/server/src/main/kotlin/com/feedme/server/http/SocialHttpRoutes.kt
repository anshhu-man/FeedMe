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
import com.feedme.server.social.SocialFailure
import com.feedme.server.social.VerifiedSocialAccount
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
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

internal val socialHttpOperations = setOf("listCircles", "createCircle", "getCircle", "updateCircle", "deleteCircle",
    "listCircleMembers", "updateCircleMember", "removeCircleMember", "leaveCircle", "transferCircleOwnership",
    "createInvitation", "previewInvitation", "acceptInvitation", "revokeInvitation")
private val socialBodyOperations = setOf("createCircle", "updateCircle", "updateCircleMember", "leaveCircle",
    "transferCircleOwnership", "createInvitation", "acceptInvitation")
private val socialReadOperations = setOf("listCircles", "getCircle", "listCircleMembers", "previewInvitation")
private val socialVersionOperations = setOf("updateCircle", "deleteCircle", "updateCircleMember", "removeCircleMember",
    "transferCircleOwnership", "revokeInvitation")
private val socialCircleOperations = setOf("getCircle", "updateCircle", "deleteCircle", "listCircleMembers",
    "updateCircleMember", "removeCircleMember", "leaveCircle", "transferCircleOwnership")

/** No raw URI, invite token, principal or header value may appear in diagnostics. */
internal class SocialHttpInput private constructor(
    val operation: String, val bearer: SocialHttpBearer?, val key: UUID?, val circleId: UUID?,
    val userId: UUID?, val invitationId: UUID?, val ifMatch: String?, val cursor: String?, val limit: Int,
    val invitationToken: SecretText?, val contentLength: Long?, val mediaType: String?,
) {
    val hasBody: Boolean get() = operation in socialBodyOperations
    override fun toString() = "SocialHttpInput(<redacted>)"

    suspend fun readBody(channel: ByteReadChannel, validator: ContractBodyValidator): JsonObject? {
        val buffer = ByteArray(if (hasBody) SocialHttpConfiguration.MAX_REQUEST_BYTES + 1 else 1)
        var size = 0
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = channel.readAvailable(buffer, size, buffer.size - size)
                if (read == -1) { channel.closedCause?.let { throw it }; break }
                size += read
                if (size == buffer.size) invalidSocial()
            }
            currentCoroutineContext().ensureActive()
            if (contentLength != null && contentLength != size.toLong()) invalidSocial()
            return body(buffer.copyOf(size), validator)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { currentCoroutineContext().ensureActive(); invalidSocial() }
        finally { buffer.fill(0) }
    }

    fun body(bytes: ByteArray, validator: ContractBodyValidator): JsonObject? {
        if (!hasBody) { if (bytes.isNotEmpty()) invalidSocial(); return null }
        if (bytes.isEmpty() || bytes.size > SocialHttpConfiguration.MAX_REQUEST_BYTES) invalidSocial()
        val document = try { WireDocument.decode(bytes, WireLimits(SocialHttpConfiguration.MAX_REQUEST_BYTES, 32)) }
            catch (_: WireDecodingException) { invalidSocial() }
        if (validator.validateRequest(operation, bytes, mediaType) != BodyValidationResult.Valid)
            throw SocialHttpFailure(422, "INPUT_INVALID")
        // Validate original bytes before tree projection: no duplicate/surrogate normalization
        // and no Double conversion of numeric lexemes before the durable command hash.
        return Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
    }

    companion object {
        fun parse(operation: String, headers: Headers, query: Parameters, paths: Parameters): SocialHttpInput {
            if (operation !in socialHttpOperations) invalidSocial()
            fun header(name: String): String? = headers.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidSocial()
                it.single()
            }
            val authorization = header(HttpHeaders.Authorization)
            // Some HTTP engines fold repeated field lines into a comma-separated value.
            // Authorization is not a list here: preserve the same ambiguity rejection.
            if (authorization?.contains(',') == true) invalidSocial()
            val device = header("X-Device-Session")
            val bearer = if (operation == "previewInvitation") {
                // Public security does not turn ordinary client headers into authority. Admit
                // well-formed optional controls, then discard them without verifier/profile I/O.
                authorization?.let {
                    val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE)
                        .matchEntire(it)?.groupValues?.get(1) ?: invalidSocial()
                    if (it.length > 16_391 || token.length !in 1..16_384 || !Regex("[A-Za-z0-9._~+/-]+=*").matches(token)) invalidSocial()
                }
                device?.let(::uuid)
                null
            } else {
                if (authorization == null || authorization.length > 16_391 || device == null)
                    throw SocialHttpFailure(401, "UNAUTHENTICATED")
                val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE)
                    .matchEntire(authorization)?.groupValues?.get(1) ?: throw SocialHttpFailure(401, "UNAUTHENTICATED")
                if (token.length !in 1..16_384 || !Regex("[A-Za-z0-9._~+/-]+=*").matches(token))
                    throw SocialHttpFailure(401, "UNAUTHENTICATED")
                SocialHttpBearer(SecretText(token), uuid(device))
            }
            val keyText = header("Idempotency-Key")
            val key = if (operation !in socialReadOperations) keyText?.let(::uuid) ?: invalidSocial()
                else { if (keyText != null) invalidSocial(); null }
            val ifMatch = header(HttpHeaders.IfMatch)
            if (operation in socialVersionOperations) {
                if (ifMatch == null) throw SocialHttpFailure(428, "PRECONDITION_REQUIRED")
                if (!Regex("\"[0-9]{1,64}\"").matches(ifMatch)) invalidSocial()
            } else if (ifMatch != null) invalidSocial()
            if (header(HttpHeaders.IfNoneMatch) != null) invalidSocial() // No canonical 304 response.
            val allowedQuery = when (operation) {
                "listCircles", "listCircleMembers" -> setOf("cursor", "limit")
                "previewInvitation" -> setOf("token")
                else -> emptySet()
            }
            if (!allowedQuery.containsAll(query.names())) invalidSocial()
            fun parameter(name: String): String? = query.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidSocial()
                it.single()
            }
            val cursor = parameter("cursor")?.also { if (it.length > 2048) invalidSocial() }
            val limit = parameter("limit")?.let {
                if (!Regex("[1-9][0-9]?").matches(it)) invalidSocial()
                it.toInt().also { n -> if (n !in 1..50) invalidSocial() }
            } ?: 20
            val token = if (operation == "previewInvitation") SecretText(parameter("token")?.also {
                if (it.length !in 32..512 || it.isBlank()) invalidSocial()
            } ?: invalidSocial()) else null
            fun pathId(name: String): UUID = paths.getAll(name)?.let {
                if (it.size != 1) invalidSocial(); uuid(it.single())
            } ?: invalidSocial()
            val circle = if (operation in socialCircleOperations) pathId("circleId") else null
            val user = if (operation in setOf("updateCircleMember", "removeCircleMember")) pathId("userId") else null
            val invitation = if (operation == "revokeInvitation") pathId("invitationId") else null
            val length = header(HttpHeaders.ContentLength)?.let {
                if (!Regex("[0-9]{1,20}").matches(it)) invalidSocial()
                it.toLongOrNull()?.takeIf { n -> n <= SocialHttpConfiguration.MAX_REQUEST_BYTES } ?: invalidSocial()
            }
            val hasBody = operation in socialBodyOperations
            val transfer = header(HttpHeaders.TransferEncoding)
            if (transfer != null && (transfer.lowercase() != "chunked" || length != null || !hasBody)) invalidSocial()
            val encoding = header(HttpHeaders.ContentEncoding)
            if (encoding != null && encoding.lowercase() != "identity") invalidSocial()
            val media = header(HttpHeaders.ContentType)
            if (hasBody) {
                if (media == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(media))
                    throw SocialHttpFailure(400, "UNSUPPORTED_MEDIA")
            } else if (media != null || (length != null && length != 0L)) invalidSocial()
            return SocialHttpInput(operation, bearer, key, circle, user, invitation, ifMatch, cursor, limit, token, length, media)
        }
        private fun uuid(value: String): UUID {
            if (!CanonicalFormats.accepts("uuid", value)) invalidSocial()
            return UUID.fromString(value)
        }
    }
}

internal suspend fun SocialHttpConfiguration.authenticate(input: SocialHttpInput): VerifiedSocialAccount {
    currentCoroutineContext().ensureActive()
    val bearer = input.bearer ?: throw SocialHttpFailure(401, "UNAUTHENTICATED")
    val result = try { verifier.verify(bearer) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw SocialHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
    currentCoroutineContext().ensureActive()
    val principal = when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> when (result.reason) {
            FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION, FailureReason.INVALID_DATA,
            FailureReason.NOT_FOUND -> throw SocialHttpFailure(401, "UNAUTHENTICATED")
            FailureReason.FORBIDDEN -> throw SocialHttpFailure(403, "FORBIDDEN")
            FailureReason.RATE_LIMITED -> throw SocialHttpFailure(429, "RATE_LIMITED", result.retryAfterSeconds)
            else -> throw SocialHttpFailure(503, "AUTHENTICATION_UNAVAILABLE", result.retryAfterSeconds)
        }
    }
    if (principal.environment != environment || principal.deviceSessionId != bearer.deviceSessionId)
        throw SocialHttpFailure(401, "UNAUTHENTICATED")
    return principal
}

internal suspend fun ApplicationCall.socialOperation(operation: String, configuration: SocialHttpConfiguration,
    validator: ContractBodyValidator) {
    try {
        currentCoroutineContext().ensureActive()
        val input = SocialHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        val actor = if (operation == "previewInvitation") null else configuration.authenticate(input)
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        val store = configuration.store
        // JDBC interruption cannot undo an already acknowledged COMMIT. Never rotate/retry a
        // command here or tell a cancelled caller that its mutation definitely did not commit.
        val reply = runInterruptible(configuration.databaseDispatcher) {
            when (operation) {
                "listCircles" -> store.listCircles(actor!!, input.cursor, input.limit)
                "getCircle" -> store.getCircle(actor!!, input.circleId!!)
                "listCircleMembers" -> store.listMembers(actor!!, input.circleId!!, input.cursor, input.limit)
                "previewInvitation" -> input.invitationToken!!.use(store::previewInvitation)
                "createCircle" -> socialReply(store.createCircle(actor!!, input.key!!, body!!))
                "updateCircle" -> socialReply(store.updateCircle(actor!!, input.key!!, input.circleId!!, input.ifMatch!!, body!!))
                "deleteCircle" -> socialReply(store.deleteCircle(actor!!, input.key!!, input.circleId!!, input.ifMatch!!))
                "updateCircleMember" -> socialReply(store.updateMember(actor!!, input.key!!, input.circleId!!, input.userId!!, input.ifMatch!!, body!!))
                "removeCircleMember" -> socialReply(store.removeMember(actor!!, input.key!!, input.circleId!!, input.userId!!, input.ifMatch!!))
                "leaveCircle" -> socialReply(store.leaveCircle(actor!!, input.key!!, input.circleId!!))
                "transferCircleOwnership" -> socialReply(store.transferOwnership(actor!!, input.key!!, input.circleId!!, input.ifMatch!!, body!!))
                "createInvitation" -> socialReply(store.createInvitation(actor!!, input.key!!, body!!))
                "acceptInvitation" -> socialReply(store.acceptInvitation(actor!!, input.key!!, body!!))
                "revokeInvitation" -> socialReply(store.revokeInvitation(actor!!, input.key!!, input.invitationId!!, input.ifMatch!!))
                else -> error("Unsupported social operation")
            }
        }
        currentCoroutineContext().ensureActive()
        val text = validateSocialReply(operation, reply, validator)
        reply.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        if (text == null) respond(HttpStatusCode.NoContent)
        else respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: SocialHttpFailure) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Social request unavailable", operationId = operation, retryAfterSeconds = failure.retryAfterSeconds) }
    catch (failure: SocialFailure) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Social request unavailable", operationId = operation) }
    catch (_: CommitOutcomeUnknown) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN", "Social outcome requires reconciliation", operationId = operation) }
}

internal fun validateSocialReply(operation: String, reply: StoredReply, validator: ContractBodyValidator): String? {
    check(operation in socialHttpOperations)
    val status = when (operation) {
        "createCircle", "createInvitation" -> 201
        "deleteCircle", "removeCircleMember", "revokeInvitation" -> 204
        else -> 200
    }
    check(reply.status == status)
    if (status == 204) {
        check(reply.body == null && reply.etag == null && validator.validateResponse(operation, status, null, null) == BodyValidationResult.Valid)
        return null
    }
    val text = reply.body?.toString() ?: error("Missing social response")
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    check(bytes.size <= 262_144 && validator.validateResponse(operation, status, bytes, "application/json") == BodyValidationResult.Valid)
    if (operation in setOf("listCircles", "listCircleMembers", "previewInvitation", "leaveCircle")) check(reply.etag == null)
    else {
        val etag = reply.etag ?: error("Missing social version")
        check(Regex("\"[0-9]+\"").matches(etag) && etag.length <= 256)
        check(BigDecimal(etag.substring(1, etag.lastIndex)).compareTo(BigDecimal(reply.body.jsonObject.getValue("version").jsonPrimitive.content)) == 0)
    }
    return text
}

private fun socialReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw SocialHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw SocialHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw SocialHttpFailure(409, "COMMAND_INCOMPLETE")
}
internal class SocialHttpFailure(val status: Int, val code: String, val retryAfterSeconds: Long? = null) : RuntimeException("Social HTTP request unavailable")
private fun invalidSocial(): Nothing = throw SocialHttpFailure(400, "INVALID_REQUEST")
