package com.feedme.server.http

import com.feedme.contracts.CanonicalFormats
import com.feedme.core.ports.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.social.posts.PostReadFailure
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import java.util.UUID
import kotlinx.coroutines.*

internal suspend fun ApplicationCall.accountRemixReadOperation(configuration: AccountRemixReadHttpConfiguration,
    validator: ContractBodyValidator) {
    val operation = "getRemixTrail"
    try {
        fun invalid(): Nothing = throw AccountPostReadHttpFailure(400, "INVALID_REQUEST")
        fun header(name: String): String? = request.headers.getAll(name)?.let {
            if (it.size != 1 || it.single().any(Char::isISOControl)) invalid(); it.single()
        }
        val auth = header(HttpHeaders.Authorization)
            ?: throw AccountPostReadHttpFailure(401, "UNAUTHENTICATED")
        val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE).matchEntire(auth)?.groupValues?.get(1)
            ?.takeIf { it.length in 1..16384 && it.matches(Regex("[A-Za-z0-9._~+/-]+=*")) }
            ?: throw AccountPostReadHttpFailure(401, "UNAUTHENTICATED")
        fun uuid(value: String?): UUID {
            if (value == null || !CanonicalFormats.accepts("uuid", value)) invalid()
            return UUID.fromString(value)
        }
        val device = uuid(header("X-Device-Session"))
        for (name in listOf("Idempotency-Key", HttpHeaders.IfMatch, HttpHeaders.IfNoneMatch,
            HttpHeaders.ContentType, HttpHeaders.TransferEncoding)) if (header(name) != null) invalid()
        header(HttpHeaders.ContentEncoding)?.let { if (!it.equals("identity", true)) invalid() }
        val length = header(HttpHeaders.ContentLength)?.let { if (!it.matches(Regex("[0-9]{1,20}")) || it.toLongOrNull() != 0L) invalid(); 0L }
        val query = request.queryParameters
        if (!setOf("cursor", "limit").containsAll(query.names()) || !setOf("postId", "cursor", "limit").containsAll(parameters.names())) invalid()
        fun param(name: String): String? = query.getAll(name)?.let {
            if (it.size != 1 || it.single().any(Char::isISOControl)) invalid(); it.single()
        }
        val post = parameters.getAll("postId")?.singleOrNull()?.let(::uuid) ?: invalid()
        val cursor = param("cursor")?.also { if (it.length !in 1..1024) invalid() }
        val limit = param("limit")?.let {
            if (!it.matches(Regex("[1-9][0-9]?"))) invalid()
            it.toInt().also { n -> if (n !in 1..20) invalid() }
        } ?: 20
        val verified = try { configuration.verifier.verify(SecretText(token)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw AccountPostReadHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
        val subject = when (verified) {
            is PortResult.Value -> verified.value
            is PortResult.Failure -> throw AccountPostReadHttpFailure(
                if (verified.reason in setOf(FailureReason.INVALID_DATA, FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION)) 401 else 503,
                if (verified.reason in setOf(FailureReason.INVALID_DATA, FailureReason.UNAUTHENTICATED, FailureReason.STALE_SESSION)) "UNAUTHENTICATED" else "AUTHENTICATION_UNAVAILABLE")
        }
        val body = readBoundedHttpBody(receiveChannel(), 0, length, ::invalid)
        try { if (body.isNotEmpty()) invalid() } finally { body.fill(0) }
        currentCoroutineContext().ensureActive()
        val reply = runInterruptible(configuration.databaseDispatcher) { configuration.store.getRemixTrail(subject, device, post, cursor, limit) }
        currentCoroutineContext().ensureActive()
        check(reply.status == 200 && reply.etag == null)
        val text = reply.body?.toString() ?: error("Missing remix response")
        val bytes = text.encodeToByteArray()
        check(bytes.size <= configuration.store.policy.maxResponseBytes &&
            validator.validateResponse(operation, 200, bytes, "application/json") == BodyValidationResult.Valid)
        response.headers.append(HttpHeaders.CacheControl, "private, no-store")
        respondText(text, ContentType.Application.Json, HttpStatusCode.OK)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: AccountPostReadHttpFailure) {
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Remix trail unavailable", operationId = operation)
    } catch (failure: PostReadFailure) {
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Remix trail unavailable", operationId = operation)
    }
}
