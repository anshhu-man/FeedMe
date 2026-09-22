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
import com.feedme.server.social.reports.ReportFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal val accountReportHttpOperations = setOf("createReport", "getMyReport")

internal class AccountReportHttpInput private constructor(
    val operation: String, val token: SecretText, val deviceSessionId: UUID,
    val key: UUID?, val reportId: UUID?, val contentLength: Long?, val mediaType: String?,
) {
    val hasBody: Boolean get() = operation == "createReport"
    override fun toString() = "AccountReportHttpInput(<redacted>)"

    suspend fun readBody(channel: ByteReadChannel, validator: ContractBodyValidator): JsonObject? {
        val bytes = readBoundedHttpBody(channel, if (hasBody) AccountReportHttpConfiguration.MAX_REQUEST_BYTES else 0,
            contentLength, ::invalidReportRequest)
        return try { body(bytes, validator) } finally { bytes.fill(0) }
    }

    fun body(bytes: ByteArray, validator: ContractBodyValidator): JsonObject? {
        if (!hasBody) { if (bytes.isNotEmpty()) invalidReportRequest(); return null }
        if (bytes.isEmpty() || bytes.size > AccountReportHttpConfiguration.MAX_REQUEST_BYTES) invalidReportRequest()
        val document = try { WireDocument.decode(bytes, WireLimits(AccountReportHttpConfiguration.MAX_REQUEST_BYTES, 8)) }
            catch (_: WireDecodingException) { invalidReportRequest() }
        if (validator.validateRequest(operation, bytes, mediaType) != BodyValidationResult.Valid)
            throw AccountReportHttpFailure(422, "INPUT_INVALID")
        return Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
    }

    companion object {
        fun parse(operation: String, headers: Headers, query: Parameters, paths: Parameters): AccountReportHttpInput {
            if (operation !in accountReportHttpOperations) invalidReportRequest()
            fun header(name: String): String? = headers.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidReportRequest()
                it.single()
            }
            val authorization = header(HttpHeaders.Authorization)
            if (authorization?.contains(',') == true) invalidReportRequest()
            val device = header("X-Device-Session")
            if (authorization == null || authorization.length > 16_391 || device == null)
                throw AccountReportHttpFailure(401, "UNAUTHENTICATED")
            val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE)
                .matchEntire(authorization)?.groupValues?.get(1) ?: throw AccountReportHttpFailure(401, "UNAUTHENTICATED")
            if (token.length !in 1..16_384 || !Regex("[A-Za-z0-9._~+/-]+=*").matches(token))
                throw AccountReportHttpFailure(401, "UNAUTHENTICATED")
            val deviceId = uuid(device)
            val keyText = header("Idempotency-Key")
            val key = if (operation == "createReport") keyText?.let(::uuid) ?: invalidReportRequest()
                else { if (keyText != null) invalidReportRequest(); null }
            if (header(HttpHeaders.IfMatch) != null || header(HttpHeaders.IfNoneMatch) != null || query.names().isNotEmpty())
                invalidReportRequest()
            val allowedPaths = if (operation == "getMyReport") setOf("reportId") else emptySet()
            if (paths.names() != allowedPaths) invalidReportRequest()
            val report = if (operation == "getMyReport") paths.getAll("reportId")?.let {
                if (it.size != 1) invalidReportRequest(); uuid(it.single())
            } ?: invalidReportRequest() else null
            val length = header(HttpHeaders.ContentLength)?.let {
                if (!Regex("[0-9]{1,20}").matches(it)) invalidReportRequest()
                it.toLongOrNull()?.takeIf { size -> size <= AccountReportHttpConfiguration.MAX_REQUEST_BYTES } ?: invalidReportRequest()
            }
            val hasBody = operation == "createReport"
            val transfer = header(HttpHeaders.TransferEncoding)
            if (transfer != null && (transfer.lowercase() != "chunked" || length != null || !hasBody)) invalidReportRequest()
            val encoding = header(HttpHeaders.ContentEncoding)
            if (encoding != null && encoding.lowercase() != "identity") invalidReportRequest()
            val media = header(HttpHeaders.ContentType)
            if (hasBody) {
                if (media == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(media))
                    throw AccountReportHttpFailure(400, "UNSUPPORTED_MEDIA")
            } else if (media != null || (length != null && length != 0L)) invalidReportRequest()
            return AccountReportHttpInput(operation, SecretText(token), deviceId, key, report, length, media)
        }

        private fun uuid(value: String): UUID {
            if (!CanonicalFormats.accepts("uuid", value)) invalidReportRequest()
            return UUID.fromString(value)
        }
    }
}

internal suspend fun ApplicationCall.accountReportOperation(operation: String, configuration: AccountReportHttpConfiguration,
    validator: ContractBodyValidator) {
    try {
        currentCoroutineContext().ensureActive()
        val input = AccountReportHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        val verified = try { configuration.verifier.verify(input.token) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw AccountReportHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
        currentCoroutineContext().ensureActive()
        val subject = when (verified) {
            is PortResult.Value -> verified.value
            is PortResult.Failure -> when (verified.reason) {
                FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION ->
                    throw AccountReportHttpFailure(401, "UNAUTHENTICATED")
                else -> throw AccountReportHttpFailure(503, "AUTHENTICATION_UNAVAILABLE")
            }
        }
        val body = input.readBody(receiveChannel(), validator)
        currentCoroutineContext().ensureActive()
        val reply = runInterruptible(configuration.databaseDispatcher) {
            when (operation) {
                "createReport" -> reportReply(configuration.store.createReport(subject, input.deviceSessionId, input.key!!, body!!))
                "getMyReport" -> configuration.store.getMyReport(subject, input.deviceSessionId, input.reportId!!)
                else -> error("Unsupported account report operation")
            }
        }
        currentCoroutineContext().ensureActive()
        val text = validateAccountReportReply(operation, reply, validator, configuration.store.policy.maxResponseBytes, input.reportId)
        response.headers.append(HttpHeaders.ETag, checkNotNull(reply.etag))
        respondText(text, ContentType.Application.Json, HttpStatusCode.fromValue(reply.status))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: AccountReportHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Report request unavailable", operationId = operation)
    } catch (failure: ReportFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Report request unavailable", operationId = operation)
    } catch (_: CommitOutcomeUnknown) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN", "Report outcome requires reconciliation", operationId = operation)
    }
}

internal fun validateAccountReportReply(operation: String, reply: StoredReply, validator: ContractBodyValidator,
    maximumBytes: Int, expectedReportId: UUID? = null): String {
    check(operation in accountReportHttpOperations && maximumBytes in 1..262_144)
    val status = if (operation == "createReport") 201 else 200
    check(reply.status == status)
    val body = reply.body ?: error("Missing report response")
    val text = body.toString()
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    check(bytes.size <= maximumBytes && validator.validateResponse(operation, status, bytes, "application/json") == BodyValidationResult.Valid)
    val etag = reply.etag ?: error("Missing report version")
    check(Regex("\"[0-9]{1,64}\"").matches(etag))
    check(BigDecimal(etag.substring(1, etag.lastIndex)).compareTo(BigDecimal(body.jsonObject.getValue("version").jsonPrimitive.content)) == 0)
    if (operation == "getMyReport") {
        check(expectedReportId != null && UUID.fromString(body.jsonObject.getValue("id").jsonPrimitive.content) == expectedReportId)
    }
    return text
}

private fun reportReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw AccountReportHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw AccountReportHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw AccountReportHttpFailure(409, "COMMAND_INCOMPLETE")
}
internal class AccountReportHttpFailure(val status: Int, val code: String) : RuntimeException("Account report HTTP request unavailable")
private fun invalidReportRequest(): Nothing = throw AccountReportHttpFailure(400, "INVALID_REQUEST")
