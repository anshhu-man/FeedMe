package com.feedme.server.http

import com.feedme.contracts.CanonicalFormats
import com.feedme.contracts.WireDecodingException
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.SecretText
import com.feedme.server.auth.VerifiedSupabaseSubject
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.CommandResult
import com.feedme.server.db.CommitOutcomeUnknown
import com.feedme.server.db.StoredReply
import com.feedme.server.identity.AccountFailure
import com.feedme.server.identity.AccountDeletionRequest
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal const val accountDeletionOperation = "requestAccountDeletion"

/** Original access token/device stay distinct from the fresh proof, which is read only from
 * the canonical JSON body. Never log this input, body, request headers or provider failures. */
internal class AccountDeletionHttpInput private constructor(
    val token: SecretText, val device: UUID, val key: UUID,
    private val contentLength: Long?, private val mediaType: String,
) {
    override fun toString() = "AccountDeletionHttpInput(<redacted>)"

    suspend fun readBody(channel: ByteReadChannel, validator: ContractBodyValidator): JsonObject {
        val bytes = readBoundedHttpBody(channel, AccountDeletionHttpConfiguration.MAX_REQUEST_BYTES,
            contentLength, ::invalidDeletion)
        return try { body(bytes, validator) } finally { bytes.fill(0) }
    }

    fun body(bytes: ByteArray, validator: ContractBodyValidator): JsonObject {
        if (bytes.isEmpty() || bytes.size > AccountDeletionHttpConfiguration.MAX_REQUEST_BYTES) invalidDeletion()
        val document = try { WireDocument.decode(bytes, WireLimits(AccountDeletionHttpConfiguration.MAX_REQUEST_BYTES, 32)) }
            catch (_: WireDecodingException) { invalidDeletion() }
        // Validate the original bytes before parsing/normalizing: duplicates, bad UTF-8 and
        // undeclared fields cannot be hidden from the canonical command fingerprint.
        if (validator.validateRequest(accountDeletionOperation, bytes, mediaType) != BodyValidationResult.Valid)
            throw AccountHttpFailure(422, "INPUT_INVALID")
        val normalized = document.encodeUtf8()
        return try {
            val result = Json.parseToJsonElement(normalized.decodeToString()).jsonObject
            // The store's additional bounded confirmation/proof checks precede even
            // original-token I/O; parsing here grants no authority and performs no effects.
            try { AccountDeletionRequest.parse(result) }
                catch (_: AccountFailure) { throw AccountHttpFailure(422, "INPUT_INVALID") }
            result
        } finally { normalized.fill(0) }
    }

    companion object {
        fun parse(headers: Headers, query: Parameters): AccountDeletionHttpInput {
            if (!query.isEmpty()) invalidDeletion()
            fun header(name: String): String? = headers.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidDeletion()
                it.single()
            }
            if (header(HttpHeaders.Upgrade) != null || header("HTTP2-Settings") != null ||
                header(HttpHeaders.Connection)?.split(',')?.any { it.trim().equals("upgrade", true) } == true)
                invalidDeletion()
            val authorization = header(HttpHeaders.Authorization)
            if (authorization?.contains(',') == true) invalidDeletion()
            if (authorization == null || authorization.length > 16_391) unauthenticatedDeletion()
            val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE)
                .matchEntire(authorization)?.groupValues?.get(1) ?: unauthenticatedDeletion()
            if (token.length !in 1..16_384 || !Regex("[A-Za-z0-9._~+/-]+=*").matches(token)) unauthenticatedDeletion()
            val device = header("X-Device-Session")?.let(::uuid) ?: unauthenticatedDeletion()
            val key = header("Idempotency-Key")?.let(::uuid) ?: invalidDeletion()
            if (header(HttpHeaders.IfMatch) != null || header(HttpHeaders.IfNoneMatch) != null) invalidDeletion()
            val length = header(HttpHeaders.ContentLength)?.let {
                if (!Regex("[0-9]{1,20}").matches(it)) invalidDeletion()
                it.toLongOrNull()?.takeIf { n -> n <= AccountDeletionHttpConfiguration.MAX_REQUEST_BYTES } ?: invalidDeletion()
            }
            val transfer = header(HttpHeaders.TransferEncoding)
            if (transfer != null && (!transfer.equals("chunked", true) || length != null)) invalidDeletion()
            val encoding = header(HttpHeaders.ContentEncoding)
            if (encoding != null && !encoding.equals("identity", true)) invalidDeletion()
            val media = header(HttpHeaders.ContentType)
            if (media == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(media))
                throw AccountHttpFailure(400, "UNSUPPORTED_MEDIA")
            return AccountDeletionHttpInput(SecretText(token), device, key, length, media)
        }

        private fun uuid(value: String): UUID {
            if (!CanonicalFormats.accepts("uuid", value)) invalidDeletion()
            return UUID.fromString(value)
        }
    }
}

internal suspend fun AccountDeletionHttpConfiguration.authenticateDeletion(input: AccountDeletionHttpInput): VerifiedSupabaseSubject {
    currentCoroutineContext().ensureActive()
    val result = try { verifier.verify(input.token) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
        catch (_: Exception) { throw AccountHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
    currentCoroutineContext().ensureActive()
    return when (result) {
        is PortResult.Value -> result.value
        is PortResult.Failure -> when (result.reason) {
            FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION, FailureReason.INVALID_DATA,
            FailureReason.NOT_FOUND -> unauthenticatedDeletion()
            FailureReason.FORBIDDEN -> throw AccountHttpFailure(403, "FORBIDDEN")
            FailureReason.RATE_LIMITED -> throw AccountHttpFailure(429, "RATE_LIMITED", result.retryAfterSeconds)
            else -> throw AccountHttpFailure(503, "AUTHENTICATION_UNAVAILABLE", result.retryAfterSeconds)
        }
    }
}

internal suspend fun ApplicationCall.accountDeletionOperation(configuration: AccountDeletionHttpConfiguration,
    validator: ContractBodyValidator) {
    try {
        currentCoroutineContext().ensureActive()
        val input = AccountDeletionHttpInput.parse(request.headers, request.queryParameters)
        // Bounded, strict input first; neither original-token nor proof-key I/O may start
        // for malformed requests. The service places fresh-proof I/O outside its DB phases.
        val body = input.readBody(receiveChannel(), validator)
        val original = configuration.authenticateDeletion(input)
        val result = configuration.service.request(original, input.device, input.key, body)
        currentCoroutineContext().ensureActive()
        val reply = when (result) {
            is CommandResult.Applied -> result.reply
            is CommandResult.Replayed -> result.reply
            CommandResult.Mismatch -> throw AccountHttpFailure(409, "IDEMPOTENCY_MISMATCH")
            CommandResult.ReceiptExpired -> throw AccountHttpFailure(410, "IDEMPOTENCY_EXPIRED")
            CommandResult.IncompleteReceipt -> throw AccountHttpFailure(409, "COMMAND_INCOMPLETE")
        }
        val text = validateAccountDeletionReply(reply, validator)
        // No Location, polling token, ETag, completion status or provider state is exposed.
        respondText(text, ContentType.Application.Json, HttpStatusCode.Accepted)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: AccountHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Account deletion request unavailable",
            operationId = accountDeletionOperation, retryAfterSeconds = failure.retryAfterSeconds)
    } catch (failure: AccountFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Account deletion request unavailable",
            operationId = accountDeletionOperation)
    } catch (_: CommitOutcomeUnknown) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN", "Account outcome requires reconciliation",
            operationId = accountDeletionOperation)
    }
}

/** A separate response contract: widening the profile adapter's 200 allowlist is forbidden. */
internal fun validateAccountDeletionReply(reply: StoredReply, validator: ContractBodyValidator): String {
    check(reply.status == 202 && reply.etag == null)
    val text = reply.body?.toString() ?: error("Missing deletion acknowledgement")
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    check(bytes.size <= 4_096 && validator.validateResponse(accountDeletionOperation, 202, bytes,
        "application/json") == BodyValidationResult.Valid)
    // The canonical Accepted schema also permits statuses used by other asynchronous work.
    // This endpoint promises immutable acceptance only, including exact committed replay.
    check(reply.body.jsonObject.keys == setOf("jobId", "status") &&
        reply.body.jsonObject.getValue("status").jsonPrimitive.content == "pending")
    return text
}

private fun invalidDeletion(): Nothing = throw AccountHttpFailure(400, "INVALID_REQUEST")
private fun unauthenticatedDeletion(): Nothing = throw AccountHttpFailure(401, "UNAUTHENTICATED")
