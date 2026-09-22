package com.feedme.server.http

import com.feedme.contracts.CanonicalFormats
import com.feedme.contracts.WireDecodingException
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.*
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.media.access.AccountMediaAccessStore
import com.feedme.server.media.access.MediaAccessFailure
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

private const val MAX_MEDIA_ACCESS_BODY = 1024
private class MediaAccessHttpInput(val token: SecretText, val device: UUID, val media: UUID,
    val length: Long?, val contentType: String) {
    override fun toString() = "MediaAccessHttpInput(<redacted>)"
}
private class MediaAccessHttpFailure(val status: Int, val code: String) : RuntimeException("Media access HTTP unavailable")
private fun invalidMediaAccess(): Nothing = throw MediaAccessHttpFailure(400, "INVALID_REQUEST")
private fun mediaUuid(value: String): UUID {
    if (!CanonicalFormats.accepts("uuid", value)) invalidMediaAccess()
    return UUID.fromString(value)
}
private fun Headers.mediaSingle(name: String): String? = getAll(name)?.let {
    if (it.size != 1 || it.single().any(Char::isISOControl)) invalidMediaAccess()
    it.single()
}
private fun ApplicationCall.mediaAccessInput(): MediaAccessHttpInput {
    if (request.queryParameters.names().isNotEmpty() || parameters.names() != setOf("mediaId")) invalidMediaAccess()
    val headers = request.headers
    val authorization = headers.mediaSingle(HttpHeaders.Authorization)
    val device = headers.mediaSingle("X-Device-Session")
    if (authorization == null || authorization.length > 16391 || device == null)
        throw MediaAccessHttpFailure(401, "UNAUTHENTICATED")
    val token = Regex("Bearer +([A-Za-z0-9._~+/=-]+)", RegexOption.IGNORE_CASE).matchEntire(authorization)?.groupValues?.get(1)
        ?: throw MediaAccessHttpFailure(401, "UNAUTHENTICATED")
    if (token.length !in 1..16384 || !Regex("[A-Za-z0-9._~+/-]+=*").matches(token))
        throw MediaAccessHttpFailure(401, "UNAUTHENTICATED")
    for (name in listOf("Idempotency-Key", HttpHeaders.IfMatch, HttpHeaders.IfNoneMatch, HttpHeaders.Range))
        if (headers.mediaSingle(name) != null) invalidMediaAccess()
    val media = parameters.getAll("mediaId")?.singleOrNull()?.let(::mediaUuid) ?: invalidMediaAccess()
    val length = headers.mediaSingle(HttpHeaders.ContentLength)?.let {
        if (!it.matches(Regex("[0-9]{1,20}"))) invalidMediaAccess()
        it.toLongOrNull()?.takeIf { value -> value in 1..MAX_MEDIA_ACCESS_BODY.toLong() } ?: invalidMediaAccess()
    }
    headers.mediaSingle(HttpHeaders.TransferEncoding)?.let {
        if (it.lowercase() != "chunked" || length != null) invalidMediaAccess()
    }
    headers.mediaSingle(HttpHeaders.ContentEncoding)?.let { if (it.lowercase() != "identity") invalidMediaAccess() }
    val contentType = headers.mediaSingle(HttpHeaders.ContentType) ?: invalidMediaAccess()
    if (!contentType.matches(Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)))
        invalidMediaAccess()
    return MediaAccessHttpInput(SecretText(token), mediaUuid(device), media, length, contentType)
}

internal suspend fun ApplicationCall.accountMediaAccessOperation(configuration: AccountMediaAccessHttpConfiguration,
    validator: ContractBodyValidator) {
    mediaPrivateHeaders()
    try {
        currentCoroutineContext().ensureActive()
        val input = mediaAccessInput()
        val verified = try { configuration.verifier.verify(input.token) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw MediaAccessHttpFailure(503, "AUTHENTICATION_UNAVAILABLE") }
        val subject = when (verified) {
            is PortResult.Value -> verified.value
            is PortResult.Failure -> if (verified.reason in setOf(FailureReason.UNAUTHENTICATED, FailureReason.INVALID_DATA, FailureReason.STALE_SESSION))
                throw MediaAccessHttpFailure(401, "UNAUTHENTICATED") else throw MediaAccessHttpFailure(503, "AUTHENTICATION_UNAVAILABLE")
        }
        val bytes = readBoundedHttpBody(receiveChannel(), MAX_MEDIA_ACCESS_BODY, input.length, ::invalidMediaAccess)
        val body = try {
            val document = try { WireDocument.decode(bytes, WireLimits(MAX_MEDIA_ACCESS_BODY, 4, 10)) }
                catch (_: WireDecodingException) { invalidMediaAccess() }
            if (validator.validateRequest("getMediaAccess", bytes, input.contentType) != BodyValidationResult.Valid)
                throw MediaAccessHttpFailure(422, "INPUT_INVALID")
            Json.parseToJsonElement(document.encodeUtf8().decodeToString()).jsonObject
        } finally { bytes.fill(0) }
        currentCoroutineContext().ensureActive()
        val reply = runInterruptible(configuration.databaseDispatcher) {
            configuration.store.getMediaAccess(subject, input.device, input.media,
                mediaUuid(body.getValue("postId").jsonPrimitive.content),
                body.getValue("surface").jsonPrimitive.content, body.getValue("variant").jsonPrimitive.content)
        }
        currentCoroutineContext().ensureActive()
        val text = reply.body?.toString() ?: throw MediaAccessHttpFailure(503, "STORAGE_UNAVAILABLE")
        if (reply.status != 200 || reply.etag != null || text.length > 4096 ||
            validator.validateResponse("getMediaAccess", 200, text.encodeToByteArray(), "application/json") != BodyValidationResult.Valid)
            throw MediaAccessHttpFailure(503, "STORAGE_UNAVAILABLE")
        respondText(text, ContentType.Application.Json, HttpStatusCode.OK)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: MediaAccessHttpFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.status), failure.code, "Media access unavailable", operationId = "getMediaAccess")
    } catch (failure: MediaAccessFailure) {
        currentCoroutineContext().ensureActive()
        problem(validator, HttpStatusCode.fromValue(failure.code.status), failure.code.name, "Media access unavailable", operationId = "getMediaAccess")
    }
}

/** Backend-owned bearer endpoint, not an additional JSON operation. Never log the path/token.
 * Only exact retained bytes are served; no redirect, range, conditional cache or origin fetch. */
internal suspend fun ApplicationCall.accountMediaByteDelivery(configuration: AccountMediaAccessHttpConfiguration) {
    mediaPrivateHeaders()
    try {
        if (request.queryParameters.names().isNotEmpty() || parameters.names() != setOf("capability")) invalidMediaAccess()
        val token = parameters.getAll("capability")?.singleOrNull() ?: invalidMediaAccess()
        if (!AccountMediaAccessStore.CAPABILITY.matches(token)) invalidMediaAccess()
        for (name in listOf(HttpHeaders.Authorization, "X-Device-Session", HttpHeaders.Cookie, HttpHeaders.Range,
            HttpHeaders.IfMatch, HttpHeaders.IfNoneMatch, HttpHeaders.IfModifiedSince, HttpHeaders.IfUnmodifiedSince,
            HttpHeaders.TransferEncoding, HttpHeaders.ContentType, HttpHeaders.ContentEncoding))
            if (request.headers.mediaSingle(name) != null) invalidMediaAccess()
        if (request.headers.mediaSingle(HttpHeaders.ContentLength)?.let { it != "0" } == true) invalidMediaAccess()
        val body = readBoundedHttpBody(receiveChannel(), 0, null, ::invalidMediaAccess)
        try { if (body.isNotEmpty()) invalidMediaAccess() } finally { body.fill(0) }
        currentCoroutineContext().ensureActive()
        val delivery = runInterruptible(configuration.databaseDispatcher) { configuration.store.openDelivery(token) }
        try {
            respond(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Image.PNG
                override val contentLength = delivery.byteCount.toLong()
                override val status = HttpStatusCode.OK
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    try {
                        val remaining = delivery.remainingMillis()
                        if (remaining <= 0 || !delivery.isCurrent()) throw CancellationException("Media delivery expired")
                        withTimeout(remaining) {
                            var offset = 0
                            while (offset < delivery.byteCount) {
                                currentCoroutineContext().ensureActive()
                                if (!delivery.isCurrent()) throw CancellationException("Media delivery expired")
                                val count = minOf(16384, delivery.byteCount - offset)
                                val chunk = delivery.copyChunk(offset, count) ?: throw CancellationException("Media delivery expired")
                                try { channel.writeFully(chunk); offset += count } finally { chunk.fill(0) }
                            }
                        }
                    } finally { delivery.close() }
                }
                override fun toString() = "VerifiedMediaContent(<redacted>)"
            })
        } catch (failure: Throwable) { delivery.close(); throw failure }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: MediaAccessHttpFailure) { respond(HttpStatusCode.NotFound) }
    catch (failure: MediaAccessFailure) {
        respond(if (failure.code.status == 429) HttpStatusCode.TooManyRequests else HttpStatusCode.NotFound)
    }
}

private fun ApplicationCall.mediaPrivateHeaders() {
    response.headers.append(HttpHeaders.CacheControl, "private, no-store, max-age=0")
    response.headers.append(HttpHeaders.Pragma, "no-cache")
    response.headers.append(HttpHeaders.Expires, "0")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("Cross-Origin-Resource-Policy", "same-origin")
}
