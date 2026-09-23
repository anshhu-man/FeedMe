package com.feedme.server.http

import com.feedme.contracts.*
import com.feedme.core.ports.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.staff.*
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal val staffModerationHttpOperations = setOf(
    "adminListReports", "adminGetCase", "adminClaimReport", "adminActOnReport", "adminListAudit", "adminGetHealth")

/** Every operation obtains actual current moderator/MFA authority in its own audited
 * transaction. A consumer token, queue item or prior HOME observation is not authority. */
internal suspend fun ApplicationCall.staffModerationOperation(operation: String,
    configuration: SupabaseStaffHttpConfiguration, validator: ContractBodyValidator) {
    response.headers.append(HttpHeaders.CacheControl, "private, no-store")
    response.headers.append(HttpHeaders.Pragma, "no-cache")
    response.headers.append("X-Content-Type-Options", "nosniff")
    try {
        currentCoroutineContext().ensureActive()
        val store = configuration.moderation ?: throw StaffModerationHttpFailure(503, "STAFF_NOT_CONFIGURED")
        if (operation !in staffModerationHttpOperations) invalidModeration()
        val writing = operation in setOf("adminClaimReport", "adminActOnReport")
        val pathName = when (operation) {
            "adminGetCase" -> "caseId"
            "adminListReports", "adminListAudit", "adminGetHealth" -> null
            else -> "reportId"
        }
        if (parameters.names() != (pathName?.let { setOf(it) } ?: emptySet<String>())) invalidModeration()
        if (request.queryParameters.names().any { operation !in setOf("adminListReports", "adminListAudit") || it !in setOf("cursor", "limit") }) invalidModeration()
        fun query(name: String): String? = request.queryParameters.getAll(name)?.let {
            if (it.size != 1 || it.single().any(Char::isISOControl)) invalidModeration(); it.single()
        }
        fun header(name: String): String? = request.headers.getAll(name)?.let {
            if (it.size != 1 || it.single().any(Char::isISOControl)) invalidModeration(); it.single()
        }
        fun uuid(value: String): UUID {
            if (!CanonicalFormats.accepts("uuid", value)) invalidModeration()
            return UUID.fromString(value)
        }
        val target = pathName?.let { name -> parameters.getAll(name)?.let {
            if (it.size != 1) invalidModeration(); uuid(it.single())
        } ?: invalidModeration() }
        val cursor = query("cursor")?.also { if (it.length !in 1..4096) invalidModeration() }
        val limit = query("limit")?.let {
            if (!it.matches(Regex("[1-9][0-9]?"))) invalidModeration()
            it.toInt().also { value -> if (value !in 1..50) invalidModeration() }
        } ?: 20
        val authorization = header(HttpHeaders.Authorization)
        if (authorization == null || authorization.length > 16391) throw StaffModerationHttpFailure(401, "STAFF_UNAUTHENTICATED")
        val token = Regex("Bearer ([A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
            .matchEntire(authorization)?.groupValues?.get(1)?.takeIf { it.length in 1..16384 }
            ?: throw StaffModerationHttpFailure(401, "STAFF_UNAUTHENTICATED")
        if (header(HttpHeaders.Cookie) != null || header("X-Device-Session") != null || header(HttpHeaders.IfNoneMatch) != null)
            invalidModeration()
        val keyText = header("Idempotency-Key"); val match = header(HttpHeaders.IfMatch)
        val key = if (writing) keyText?.let(::uuid) ?: invalidModeration() else {
            if (keyText != null || match != null) invalidModeration(); null
        }
        if (operation == "adminActOnReport") {
            if (match == null) throw StaffModerationHttpFailure(428, "PRECONDITION_REQUIRED")
            if (!match.matches(Regex("\"[1-9][0-9]{0,18}\"")) || match.removeSurrounding("\"").toLongOrNull() == null) invalidModeration()
        } else if (match != null) invalidModeration()
        val length = header(HttpHeaders.ContentLength)?.let {
            if (!it.matches(Regex("[0-9]{1,5}"))) invalidModeration()
            it.toLong().also { count -> if (count > if (writing) 16384L else 0L) invalidModeration() }
        }
        val transfer = header(HttpHeaders.TransferEncoding)
        if (transfer != null && (!writing || length != null || transfer.lowercase() != "chunked")) invalidModeration()
        if (header(HttpHeaders.ContentEncoding)?.lowercase()?.let { it != "identity" } == true) invalidModeration()
        val media = header(HttpHeaders.ContentType)
        if (writing) {
            if (media == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(media))
                throw StaffModerationHttpFailure(400, "UNSUPPORTED_MEDIA")
        } else if (media != null) invalidModeration()
        val bytes = readBoundedHttpBody(receiveChannel(), if (writing) 16384 else 0, length, ::invalidModeration)
        val body = try {
            if (!writing) { if (bytes.isNotEmpty()) invalidModeration(); null }
            else {
                val decoded = try { WireDocument.decode(bytes, WireLimits(16384, 8)) }
                    catch (_: WireDecodingException) { invalidModeration() }
                if (validator.validateRequest(operation, bytes, media) != BodyValidationResult.Valid)
                    throw StaffModerationHttpFailure(422, "INPUT_INVALID")
                Json.parseToJsonElement(decoded.encodeUtf8().decodeToString()).jsonObject
            }
        } finally { bytes.fill(0) }
        val verified = try { configuration.verifier.verify(SecretText(token)) }
            catch (e: CancellationException) { throw e }
            catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
            catch (_: Exception) { throw StaffModerationHttpFailure(503, "STAFF_UNAVAILABLE") }
        val subject = when (verified) {
            is PortResult.Value -> verified.value
            is PortResult.Failure -> if (verified.reason in setOf(FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION))
                throw StaffModerationHttpFailure(401, "STAFF_UNAUTHENTICATED") else throw StaffModerationHttpFailure(503, "STAFF_UNAVAILABLE")
        }
        val trace = httpObservationTraceId()
        val reply = runInterruptible(configuration.databaseDispatcher) {
            when (operation) {
                "adminListReports" -> store.adminListReports(subject, cursor, limit, trace)
                "adminListAudit" -> store.adminListAudit(subject, cursor, limit, trace)
                "adminGetHealth" -> store.adminGetHealth(subject, trace)
                "adminGetCase" -> store.adminGetCase(subject, target!!, trace)
                "adminClaimReport" -> moderationReply(store.adminClaimReport(subject, target!!, key!!, body!!, trace))
                "adminActOnReport" -> moderationReply(when (body!!["action"]) {
                    JsonPrimitive("remove") -> store.adminRemoveReport(subject, target!!, key!!, match!!, body, trace)
                    JsonPrimitive("dismiss") -> store.adminDismissReport(subject, target!!, key!!, match!!, body, trace)
                    else -> throw StaffModerationHttpFailure(422, "INPUT_INVALID")
                })
                else -> invalidModeration()
            }
        }
        currentCoroutineContext().ensureActive()
        val text = checkNotNull(reply.body).toString()
        check(reply.status == 200 && text.encodeToByteArray().size <= store.policy.maxResponseBytes &&
            validator.validateResponse(operation, reply.status, text.encodeToByteArray(), "application/json") == BodyValidationResult.Valid)
        reply.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        respondText(text, ContentType.Application.Json, HttpStatusCode.OK)
    } catch (e: CancellationException) { throw e }
      catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
      catch (e: StaffModerationHttpFailure) { currentCoroutineContext().ensureActive()
          problem(validator, HttpStatusCode.fromValue(e.status), e.code, "Staff moderation unavailable", operationId = operation) }
      catch (e: SupabaseStaffFailure) { currentCoroutineContext().ensureActive()
          problem(validator, HttpStatusCode.fromValue(e.code.status), e.code.name, "Staff moderation access unavailable", operationId = operation) }
      catch (e: StaffModerationFailure) { currentCoroutineContext().ensureActive()
          problem(validator, HttpStatusCode.fromValue(e.code.status), e.code.name, "Staff moderation unavailable", operationId = operation) }
      catch (_: CommitOutcomeUnknown) { currentCoroutineContext().ensureActive()
          problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN", "Retain the original moderation command", operationId = operation) }
}

private fun moderationReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw StaffModerationHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw StaffModerationHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw StaffModerationHttpFailure(409, "COMMAND_INCOMPLETE")
}
private class StaffModerationHttpFailure(val status: Int, val code: String) : RuntimeException("Staff moderation HTTP unavailable")
private fun invalidModeration(): Nothing = throw StaffModerationHttpFailure(400, "INVALID_REQUEST")
