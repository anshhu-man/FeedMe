package com.feedme.server.http

import com.feedme.contracts.CanonicalFormats
import com.feedme.contracts.WireDecodingException
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.*
import com.feedme.server.identity.AccountFailure
import com.feedme.server.identity.NotificationInboxFailure
import com.feedme.server.social.SocialFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal val accountNotificationInboxHttpOperations = setOf(
    "listNotifications", "markNotificationRead", "markNotificationsRead")

/** Canonical Inbox only; a caller-supplied notification ID/timestamp is a target, never
 * authorization. Neither write accepts If-Match, and reading never advances read state. */
internal class AccountNotificationInboxHttpInput private constructor(
    val operation: String, val token: SecretText, val device: UUID, val key: UUID?,
    val notificationId: UUID?, val cursor: String?, val limit: Int,
    private val contentLength: Long?, private val mediaType: String?,
) {
    override fun toString() = "AccountNotificationInboxHttpInput(<redacted>)"
    suspend fun readBody(channel: ByteReadChannel, validator: ContractBodyValidator): JsonObject? {
        val writing = operation != "listNotifications"
        val bytes = readBoundedHttpBody(channel,
            if (writing) AccountNotificationInboxHttpConfiguration.MAX_REQUEST_BYTES else 0,
            contentLength, ::invalidNotificationInbox)
        return try {
            if (!writing) { if (bytes.isNotEmpty()) invalidNotificationInbox(); null }
            else {
                if (bytes.isEmpty()) invalidNotificationInbox()
                val document = try { WireDocument.decode(bytes, WireLimits(AccountNotificationInboxHttpConfiguration.MAX_REQUEST_BYTES, 4)) }
                    catch (_: WireDecodingException) { invalidNotificationInbox() }
                if (validator.validateRequest(operation, bytes, mediaType) != BodyValidationResult.Valid)
                    throw NotificationInboxHttpFailure(422, "INPUT_INVALID")
                Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
            }
        } finally { bytes.fill(0) }
    }
    companion object {
        fun parse(operation: String, headers: Headers, query: Parameters, paths: Parameters): AccountNotificationInboxHttpInput {
            if (operation !in accountNotificationInboxHttpOperations) invalidNotificationInbox()
            val listing = operation == "listNotifications"
            fun header(name: String): String? = headers.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidNotificationInbox()
                it.single()
            }
            val authorization = header(HttpHeaders.Authorization)
            val device = header("X-Device-Session")
            if (authorization == null || authorization.length > 16391 || device == null)
                throw NotificationInboxHttpFailure(401, "UNAUTHENTICATED")
            val token = Regex("Bearer +([A-Za-z0-9._~+/-]+=*)", RegexOption.IGNORE_CASE)
                .matchEntire(authorization)?.groupValues?.get(1)?.takeIf { it.length in 1..16384 }
                ?: throw NotificationInboxHttpFailure(401, "UNAUTHENTICATED")
            val keyText = header("Idempotency-Key")
            val key = if (listing) { if (keyText != null) invalidNotificationInbox(); null }
                else keyText?.let(::uuid) ?: invalidNotificationInbox()
            if (header(HttpHeaders.IfMatch) != null || header(HttpHeaders.IfNoneMatch) != null) invalidNotificationInbox()
            val expectedPaths = if (operation == "markNotificationRead") setOf("notificationId") else emptySet()
            // Ktor may expose query parameters in the combined call parameters. Only the
            // separately validated canonical query names may accompany the exact path.
            if (!(expectedPaths + query.names()).containsAll(paths.names())) invalidNotificationInbox()
            val notificationId = if (operation == "markNotificationRead") paths.getAll("notificationId")?.let {
                if (it.size != 1) invalidNotificationInbox(); uuid(it.single())
            } ?: invalidNotificationInbox() else null
            if (query.names().any { it !in setOf("cursor", "limit") } || (!listing && query.names().isNotEmpty()))
                invalidNotificationInbox()
            fun parameter(name: String): String? = query.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidNotificationInbox(); it.single()
            }
            val cursor = parameter("cursor")?.also { if (it.length !in 1..2048) invalidNotificationInbox() }
            val limit = parameter("limit")?.let {
                if (!it.matches(Regex("[1-9][0-9]?"))) invalidNotificationInbox()
                it.toInt().takeIf { value -> value in 1..50 } ?: invalidNotificationInbox()
            } ?: 20
            val length = header(HttpHeaders.ContentLength)?.let {
                if (!it.matches(Regex("[0-9]{1,5}"))) invalidNotificationInbox()
                it.toLongOrNull()?.takeIf { size -> size <= if (listing) 0 else AccountNotificationInboxHttpConfiguration.MAX_REQUEST_BYTES }
                    ?: invalidNotificationInbox()
            }
            val transfer = header(HttpHeaders.TransferEncoding)
            if (transfer != null && (listing || length != null || transfer.lowercase() != "chunked")) invalidNotificationInbox()
            if (header(HttpHeaders.ContentEncoding)?.lowercase()?.let { it != "identity" } == true) invalidNotificationInbox()
            val media = header(HttpHeaders.ContentType)
            if (listing) { if (media != null) invalidNotificationInbox() }
            else if (media == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(media))
                throw NotificationInboxHttpFailure(400, "UNSUPPORTED_MEDIA")
            return AccountNotificationInboxHttpInput(operation, SecretText(token), uuid(device), key,
                notificationId, cursor, limit, length, media)
        }
        private fun uuid(value: String): UUID {
            if (!CanonicalFormats.accepts("uuid", value)) invalidNotificationInbox()
            return UUID.fromString(value)
        }
    }
}

internal suspend fun ApplicationCall.accountNotificationInboxOperation(operation: String,
    configuration: AccountNotificationInboxHttpConfiguration, validator: ContractBodyValidator) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    try {
        currentCoroutineContext().ensureActive()
        val input = AccountNotificationInboxHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        val body = input.readBody(receiveChannel(), validator)
        val verified = try { configuration.verifier.verify(input.token) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw NotificationInboxHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
        currentCoroutineContext().ensureActive()
        val subject = when (verified) {
            is PortResult.Value -> verified.value
            is PortResult.Failure -> if (verified.reason in setOf(FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION))
                throw NotificationInboxHttpFailure(401, "UNAUTHENTICATED")
                else throw NotificationInboxHttpFailure(503, "AUTHENTICATION_UNAVAILABLE")
        }
        val reply = runInterruptible(configuration.databaseDispatcher) {
            val store = configuration.store
            when (operation) {
                "listNotifications" -> store.listNotifications(subject, input.device, input.limit, input.cursor)
                "markNotificationRead" -> notificationInboxReply(store.markNotificationRead(subject,
                    input.device, input.key!!, input.notificationId!!, body!!))
                "markNotificationsRead" -> notificationInboxReply(store.markNotificationsRead(subject, input.device, input.key!!, body!!))
                else -> error("Unsupported notification Inbox operation")
            }
        }
        currentCoroutineContext().ensureActive()
        val text = validateAccountNotificationInboxReply(operation, reply, validator,
            configuration.store.policy.maxResponseBytes, input.notificationId)
        reply.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        respondText(text, ContentType.Application.Json, HttpStatusCode.OK)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: NotificationInboxHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Notification Inbox unavailable", operationId = operation)
    } catch (failure: NotificationInboxFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Notification Inbox unavailable", operationId = operation)
    } catch (failure: AccountFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Notification Inbox unavailable", operationId = operation)
    } catch (failure: SocialFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Notification Inbox unavailable", operationId = operation)
    } catch (_: CommitOutcomeUnknown) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.ServiceUnavailable, "OUTCOME_UNKNOWN", "Notification read outcome requires reconciliation", operationId = operation)
    }
}

internal fun validateAccountNotificationInboxReply(operation: String, reply: StoredReply,
    validator: ContractBodyValidator, maximumBytes: Int, notificationId: UUID? = null): String {
    check(operation in accountNotificationInboxHttpOperations && reply.status == 200 && maximumBytes in 4096..262144)
    val body = checkNotNull(reply.body).jsonObject
    val text = body.toString(); val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    check(bytes.size <= maximumBytes && validator.validateResponse(operation, 200, bytes, "application/json") == BodyValidationResult.Valid)
    if (operation == "markNotificationRead") {
        val etag = checkNotNull(reply.etag)
        check(etag.matches(Regex("\"[1-9][0-9]{0,18}\"")) && etag.drop(1).dropLast(1).toLongOrNull() != null)
        check(BigDecimal(etag.drop(1).dropLast(1)).compareTo(BigDecimal(body.getValue("version").jsonPrimitive.content)) == 0)
        check(notificationId != null && UUID.fromString(body.getValue("id").jsonPrimitive.content) == notificationId && "readAt" in body)
    } else check(reply.etag == null)
    return text
}
private fun notificationInboxReply(result: CommandResult): StoredReply = when (result) {
    is CommandResult.Applied -> result.reply
    is CommandResult.Replayed -> result.reply
    CommandResult.Mismatch -> throw NotificationInboxHttpFailure(409, "IDEMPOTENCY_MISMATCH")
    CommandResult.ReceiptExpired -> throw NotificationInboxHttpFailure(410, "IDEMPOTENCY_EXPIRED")
    CommandResult.IncompleteReceipt -> throw NotificationInboxHttpFailure(409, "COMMAND_INCOMPLETE")
}
internal class NotificationInboxHttpFailure(val status: Int, val code: String) : RuntimeException("Notification Inbox HTTP unavailable")
private fun invalidNotificationInbox(): Nothing = throw NotificationInboxHttpFailure(400, "INVALID_REQUEST")
