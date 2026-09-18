package com.feedme.server.http

import com.feedme.contracts.CanonicalFormats
import com.feedme.contracts.WireDecodingException
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.*
import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandResult
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.StoredReply
import com.feedme.server.identity.AccountFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.*

internal val accountHttpOperations = setOf("bootstrapAccount", "getMe", "getAccountReady", "updateMe", "logoutSession")
private val accountReadOperations = setOf("getMe", "getAccountReady")

/** Never log request metadata, provider tokens, installation IDs or private profile bodies. */
internal class AccountHttpInput private constructor(
    val operation: String, val token: SecretText, val device: UUID?, val key: UUID?,
    val ifMatch: String?, val contentLength: Long?, val mediaType: String?,
) {
    val hasBody: Boolean get() = operation !in accountReadOperations
    override fun toString() = "AccountHttpInput(<redacted>)"

    suspend fun readBody(channel: ByteReadChannel, validator: ContractBodyValidator): JsonObject? {
        val exact = readBoundedHttpBody(channel, if (hasBody) AccountHttpConfiguration.MAX_REQUEST_BYTES else 0, contentLength, ::invalidAccount)
        return try { body(exact, validator) } finally { exact.fill(0) }
    }

    fun body(bytes: ByteArray, validator: ContractBodyValidator): JsonObject? {
        if (!hasBody) { if (bytes.isNotEmpty()) invalidAccount(); return null }
        if (bytes.isEmpty() || bytes.size > AccountHttpConfiguration.MAX_REQUEST_BYTES) invalidAccount()
        val document = try { WireDocument.decode(bytes, WireLimits(AccountHttpConfiguration.MAX_REQUEST_BYTES, 32)) }
            catch (_: WireDecodingException) { invalidAccount() }
        if (validator.validateRequest(operation, bytes, mediaType) != BodyValidationResult.Valid)
            throw AccountHttpFailure(422, "INPUT_INVALID")
        // Validate original bytes first: duplicate fields, bad UTF-8 and numeric lexemes are
        // never normalized before the store's canonical durable-command fingerprint.
        val result = Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
        if (operation == "updateMe" && result.isEmpty()) throw AccountHttpFailure(422, "INPUT_INVALID")
        return result
    }

    companion object {
        fun parse(operation: String, headers: Headers, query: Parameters): AccountHttpInput {
            if (operation !in accountHttpOperations || !query.isEmpty()) invalidAccount()
            fun header(name: String): String? = headers.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidAccount()
                it.single()
            }
            // These JSON account routes do not implement a protocol upgrade. CIO exposes
            // an open upgrade channel for GET + Connection: upgrade, not an empty body;
            // reject negotiation before authentication/receiveChannel, never skip body checks.
            if (header(HttpHeaders.Upgrade) != null || header("HTTP2-Settings") != null ||
                header(HttpHeaders.Connection)?.split(',')?.any { it.trim().equals("upgrade", ignoreCase = true) } == true)
                invalidAccount()
            val authorization = header(HttpHeaders.Authorization)
            if (authorization?.contains(',') == true) invalidAccount()
            if (authorization == null || authorization.length > 16_391) unauthenticatedAccount()
            val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE)
                .matchEntire(authorization)?.groupValues?.get(1) ?: unauthenticatedAccount()
            if (token.length !in 1..16_384 || !Regex("[A-Za-z0-9._~+/-]+=*").matches(token)) unauthenticatedAccount()
            val deviceText = header("X-Device-Session")
            // Bootstrap has no device yet. Do not accept a caller-selected binding there.
            val device = if (operation == "bootstrapAccount") {
                if (deviceText != null) invalidAccount(); null
            } else deviceText?.let(::uuid) ?: unauthenticatedAccount()
            val keyText = header("Idempotency-Key")
            val key = if (operation in accountReadOperations) { if (keyText != null) invalidAccount(); null }
                else keyText?.let(::uuid) ?: invalidAccount()
            val ifMatch = header(HttpHeaders.IfMatch)
            if (operation == "updateMe") {
                if (ifMatch == null) throw AccountHttpFailure(428, "PRECONDITION_REQUIRED")
                if (!Regex("\"[1-9][0-9]{0,18}\"").matches(ifMatch) ||
                    ifMatch.substring(1, ifMatch.lastIndex).toLongOrNull() == null) invalidAccount()
            } else if (ifMatch != null) invalidAccount()
            if (header(HttpHeaders.IfNoneMatch) != null) invalidAccount()
            val length = header(HttpHeaders.ContentLength)?.let {
                if (!Regex("[0-9]{1,20}").matches(it)) invalidAccount()
                it.toLongOrNull()?.takeIf { n -> n <= AccountHttpConfiguration.MAX_REQUEST_BYTES } ?: invalidAccount()
            }
            val hasBody = operation !in accountReadOperations
            val transfer = header(HttpHeaders.TransferEncoding)
            if (transfer != null && (transfer.lowercase() != "chunked" || length != null || !hasBody)) invalidAccount()
            val encoding = header(HttpHeaders.ContentEncoding)
            if (encoding != null && encoding.lowercase() != "identity") invalidAccount()
            val media = header(HttpHeaders.ContentType)
            if (hasBody) {
                if (media == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(media))
                    throw AccountHttpFailure(400, "UNSUPPORTED_MEDIA")
            } else if (media != null || (length != null && length != 0L)) invalidAccount()
            return AccountHttpInput(operation, SecretText(token), device, key, ifMatch, length, media)
        }
        private fun uuid(value: String): UUID {
            if (!CanonicalFormats.accepts("uuid", value)) invalidAccount()
            return UUID.fromString(value)
        }
    }
}

internal suspend fun AccountHttpConfiguration.authenticate(input: AccountHttpInput): VerifiedSupabaseSubject {
    currentCoroutineContext().ensureActive()
    val result = try { verifier.verify(input.token) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw AccountHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
    currentCoroutineContext().ensureActive()
    return when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> when (result.reason) {
            FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION, FailureReason.INVALID_DATA,
            FailureReason.NOT_FOUND -> unauthenticatedAccount()
            FailureReason.FORBIDDEN -> throw AccountHttpFailure(403, "FORBIDDEN")
            FailureReason.RATE_LIMITED -> throw AccountHttpFailure(429, "RATE_LIMITED", result.retryAfterSeconds)
            else -> throw AccountHttpFailure(503, "AUTHENTICATION_UNAVAILABLE", result.retryAfterSeconds)
        }
    }
}

internal suspend fun ApplicationCall.accountOperation(operation: String, configuration: AccountHttpConfiguration,
    validator: ContractBodyValidator) {
    try {
        currentCoroutineContext().ensureActive()
        val input = AccountHttpInput.parse(operation, request.headers, request.queryParameters)
        val subject = configuration.authenticate(input)
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        // Only the existing store owns transactions, current policy and exact-key replay.
        // Cancellation cannot prove a COMMIT did not happen; never auto retry or mint a key.
        val reply = runInterruptible(configuration.databaseDispatcher) {
            when (operation) {
                "bootstrapAccount" -> accountReply(configuration.store.bootstrapAccount(subject, input.key!!, body!!))
                "getMe" -> configuration.store.getMe(subject, input.device!!)
                "getAccountReady" -> configuration.store.getAccountReady(subject, input.device!!)
                "logoutSession" -> accountReply(configuration.store.logoutSession(subject, input.device!!, input.key!!, body!!))
                "updateMe" -> accountReply(configuration.store.updateMe(subject, input.device!!, input.key!!, input.ifMatch!!, body!!))
                else -> error("Unsupported account operation")
            }
        }
        currentCoroutineContext().ensureActive()
        val text = validateAccountReply(operation, reply, validator)
        reply.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        respondText(text, ContentType.Application.Json, HttpStatusCode.OK)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: AccountHttpFailure) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Account request unavailable", operationId = operation, retryAfterSeconds = failure.retryAfterSeconds) }
    catch (failure: AccountFailure) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Account request unavailable", operationId = operation) }
    catch (_: CommitOutcomeUnknown) { currentCoroutineContext().ensureActive(); problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN", "Account outcome requires reconciliation", operationId = operation) }
}

internal fun validateAccountReply(operation: String, reply: StoredReply, validator: ContractBodyValidator): String {
    check(operation in accountHttpOperations && reply.status == 200)
    val text = reply.body?.toString() ?: error("Missing account response")
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    check(bytes.size <= 262_144 && validator.validateResponse(operation, 200, bytes, "application/json") == BodyValidationResult.Valid)
    if (operation in setOf("bootstrapAccount", "getAccountReady", "logoutSession")) check(reply.etag == null)
    else {
        val etag = reply.etag ?: error("Missing profile version")
        check(Regex("\"[1-9][0-9]{0,18}\"").matches(etag))
        check(etag.substring(1, etag.lastIndex).toLongOrNull() != null)
        check(BigDecimal(etag.substring(1, etag.lastIndex)).compareTo(BigDecimal(reply.body.jsonObject.getValue("version").jsonPrimitive.content)) == 0)
    }
    return text
}

private fun accountReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw AccountHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw AccountHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw AccountHttpFailure(409, "COMMAND_INCOMPLETE")
}
internal class AccountHttpFailure(val status: Int, val code: String, val retryAfterSeconds: Long? = null) : RuntimeException("Account HTTP request unavailable")
private fun invalidAccount(): Nothing = throw AccountHttpFailure(400, "INVALID_REQUEST")
private fun unauthenticatedAccount(): Nothing = throw AccountHttpFailure(401, "UNAUTHENTICATED")
