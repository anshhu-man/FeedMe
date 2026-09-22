package com.feedme.server.http

import com.feedme.contracts.CanonicalFormats
import com.feedme.core.ports.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.db.StoredReply
import com.feedme.server.social.posts.PostReadFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal val accountPostReadHttpOperations = setOf("getToday", "getProfilePlate", "getPost")

internal class AccountPostReadHttpInput private constructor(
    val operation: String, val token: SecretText, val deviceSessionId: UUID,
    val postId: UUID?, val userId: UUID?, val circleId: UUID?,
    val cursor: String?, val limit: Int, val surface: String, val contentLength: Long?,
) {
    override fun toString() = "AccountPostReadHttpInput(<redacted>)"
    suspend fun requireNoBody(channel: ByteReadChannel) {
        val bytes = readBoundedHttpBody(channel, 0, contentLength, ::invalidPostRead)
        try { if (bytes.isNotEmpty()) invalidPostRead() } finally { bytes.fill(0) }
    }
    companion object {
        fun parse(operation: String, headers: Headers, query: Parameters, paths: Parameters): AccountPostReadHttpInput {
            if (operation !in accountPostReadHttpOperations) invalidPostRead()
            fun header(name: String): String? = headers.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidPostRead()
                it.single()
            }
            val authorization = header(HttpHeaders.Authorization)
            if (authorization?.contains(',') == true) invalidPostRead()
            val device = header("X-Device-Session")
            if (authorization == null || authorization.length > 16_391 || device == null)
                throw AccountPostReadHttpFailure(401, "UNAUTHENTICATED")
            val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE)
                .matchEntire(authorization)?.groupValues?.get(1) ?: throw AccountPostReadHttpFailure(401, "UNAUTHENTICATED")
            if (token.length !in 1..16_384 || !Regex("[A-Za-z0-9._~+/-]+=*").matches(token))
                throw AccountPostReadHttpFailure(401, "UNAUTHENTICATED")
            val deviceId = uuid(device)
            for (name in listOf("Idempotency-Key", HttpHeaders.IfMatch, HttpHeaders.IfNoneMatch,
                HttpHeaders.ContentType, HttpHeaders.TransferEncoding)) if (header(name) != null) invalidPostRead()
            header(HttpHeaders.ContentEncoding)?.let { if (!it.equals("identity", ignoreCase = true)) invalidPostRead() }
            val length = header(HttpHeaders.ContentLength)?.let {
                if (!Regex("[0-9]{1,20}").matches(it) || it.toLongOrNull() != 0L) invalidPostRead()
                0L
            }
            val allowedQuery = when (operation) {
                "getToday" -> setOf("cursor", "limit", "circleId")
                "getProfilePlate" -> setOf("cursor", "limit")
                else -> setOf("surface")
            }
            if (!allowedQuery.containsAll(query.names())) invalidPostRead()
            fun parameter(name: String): String? = query.getAll(name)?.let {
                if (it.size != 1 || it.single().any(Char::isISOControl)) invalidPostRead()
                it.single()
            }
            val cursor = parameter("cursor")?.also { if (it.length !in 1..2048) invalidPostRead() }
            val limit = parameter("limit")?.let {
                if (!Regex("[1-9][0-9]?").matches(it)) invalidPostRead()
                it.toInt().also { value -> if (value !in 1..50) invalidPostRead() }
            } ?: 20
            val surface = parameter("surface")?.also { if (it !in setOf("today", "plate", "detail")) invalidPostRead() } ?: "detail"
            val circle = parameter("circleId")?.let(::uuid)
            val routeKeys = when (operation) { "getPost" -> setOf("postId"); "getProfilePlate" -> setOf("userId"); else -> emptySet() }
            // Ktor merges route and already-validated query parameters in call.parameters.
            if (!(routeKeys + query.names()).containsAll(paths.names())) invalidPostRead()
            fun path(name: String): UUID = paths.getAll(name)?.let {
                if (it.size != 1) invalidPostRead(); uuid(it.single())
            } ?: invalidPostRead()
            return AccountPostReadHttpInput(operation, SecretText(token), deviceId,
                if (operation == "getPost") path("postId") else null,
                if (operation == "getProfilePlate") path("userId") else null,
                circle, cursor, limit, surface, length)
        }
        private fun uuid(value: String): UUID {
            if (!CanonicalFormats.accepts("uuid", value)) invalidPostRead()
            return UUID.fromString(value)
        }
    }
}

internal suspend fun ApplicationCall.accountPostReadOperation(operation: String, configuration: AccountPostReadHttpConfiguration,
    validator: ContractBodyValidator) {
    try {
        currentCoroutineContext().ensureActive()
        val input = AccountPostReadHttpInput.parse(operation, request.headers, request.queryParameters, parameters)
        val verified = try { configuration.verifier.verify(input.token) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw AccountPostReadHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
        currentCoroutineContext().ensureActive()
        val subject = when (verified) {
            is PortResult.Value -> verified.value
            is PortResult.Failure -> when (verified.reason) {
                FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION ->
                    throw AccountPostReadHttpFailure(401, "UNAUTHENTICATED")
                else -> throw AccountPostReadHttpFailure(503, "AUTHENTICATION_UNAVAILABLE")
            }
        }
        input.requireNoBody(receiveChannel())
        currentCoroutineContext().ensureActive()
        val reply = runInterruptible(configuration.databaseDispatcher) {
            when (operation) {
                "getToday" -> configuration.store.getToday(subject, input.deviceSessionId, input.cursor, input.limit, input.circleId)
                "getProfilePlate" -> configuration.store.getProfilePlate(subject, input.deviceSessionId, input.userId!!, input.cursor, input.limit)
                "getPost" -> configuration.store.getPost(subject, input.deviceSessionId, input.postId!!, input.surface)
                else -> error("Unsupported account post read")
            }
        }
        currentCoroutineContext().ensureActive()
        val text = validateAccountPostReadReply(operation, reply, validator, configuration.store.policy.maxResponseBytes, input.postId)
        reply.etag?.let { response.headers.append(HttpHeaders.ETag, it) }
        respondText(text, ContentType.Application.Json, HttpStatusCode.OK)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: AccountPostReadHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Post request unavailable", operationId = operation)
    } catch (failure: PostReadFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Post request unavailable", operationId = operation)
    }
}

internal fun validateAccountPostReadReply(operation: String, reply: StoredReply, validator: ContractBodyValidator,
    maximumBytes: Int, expectedPostId: UUID? = null): String {
    check(operation in accountPostReadHttpOperations && maximumBytes in 1..262_144 && reply.status == 200)
    val text = reply.body?.toString() ?: error("Missing post response")
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    check(bytes.size <= maximumBytes && validator.validateResponse(operation, 200, bytes, "application/json") == BodyValidationResult.Valid)
    if (operation != "getPost") check(reply.etag == null && expectedPostId == null)
    else {
        check(expectedPostId != null && reply.body.jsonObject.getValue("id").jsonPrimitive.content == expectedPostId.toString())
        val etag = reply.etag ?: error("Missing post version")
        check(Regex("\"[0-9]{1,64}\"").matches(etag))
        check(BigDecimal(etag.substring(1, etag.lastIndex)).compareTo(BigDecimal(reply.body.jsonObject.getValue("version").jsonPrimitive.content)) == 0)
    }
    return text
}
internal class AccountPostReadHttpFailure(val status: Int, val code: String) : RuntimeException("Account post read unavailable")
private fun invalidPostRead(): Nothing = throw AccountPostReadHttpFailure(400, "INVALID_REQUEST")
