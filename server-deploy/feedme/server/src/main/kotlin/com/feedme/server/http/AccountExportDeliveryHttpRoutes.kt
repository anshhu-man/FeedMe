package com.feedme.server.http

import com.feedme.server.export.ExportDelivery
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.*

/** The opaque URL is a secret. No redirects, ordinary bearer token, caching or range reads. */
internal suspend fun ApplicationCall.accountExportByteDelivery(configuration: AccountExportDeliveryHttpConfiguration) {
    response.headers.append(HttpHeaders.CacheControl, "private, no-store, max-age=0")
    response.headers.append(HttpHeaders.Pragma, "no-cache")
    response.headers.append(HttpHeaders.Expires, "0")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("Cross-Origin-Resource-Policy", "same-origin")
    response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"feedme-account-export.json\"")
    var responded = false
    try {
        fun invalid(): Nothing = throw IllegalArgumentException("Export delivery unavailable")
        fun header(name: String): String? = request.headers.getAll(name)?.let {
            if (it.size != 1 || it.single().any(Char::isISOControl)) invalid(); it.single()
        }
        if (request.queryParameters.names().isNotEmpty() || parameters.names() != setOf("capability")) invalid()
        val capability = parameters.getAll("capability")?.singleOrNull()
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{43}")) } ?: invalid()
        for (name in listOf(HttpHeaders.Authorization, "X-Device-Session", HttpHeaders.Cookie, HttpHeaders.Range,
            HttpHeaders.IfMatch, HttpHeaders.IfNoneMatch, HttpHeaders.IfModifiedSince, HttpHeaders.IfUnmodifiedSince,
            HttpHeaders.TransferEncoding, HttpHeaders.ContentType, HttpHeaders.ContentEncoding)) if (header(name) != null) invalid()
        if (header(HttpHeaders.ContentLength)?.let { it != "0" } == true) invalid()
        val body = readBoundedHttpBody(receiveChannel(), 0, null, ::invalid)
        try { if (body.isNotEmpty()) invalid() } finally { body.fill(0) }
        var opened: ExportDelivery? = null
        val delivery = try {
            runInterruptible(configuration.databaseDispatcher) {
                configuration.downloads.openDelivery(capability).also { opened = it }
            } ?: invalid()
        } catch (failure: Throwable) {
            // Prompt cancellation can discard a successful dispatcher result. Keep
            // ownership until that handoff so private bytes/admission are released.
            opened?.close()
            throw failure
        }
        try {
            responded = true
            respond(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Application.Json
                override val contentLength = delivery.byteCount.toLong()
                override val status = HttpStatusCode.OK
                override suspend fun writeTo(channel: ByteWriteChannel) {
                    try {
                        val remaining = delivery.remainingMillis()
                        if (remaining <= 0 || !delivery.isCurrent()) throw CancellationException("Export delivery expired")
                        withTimeout(remaining) {
                            var offset = 0
                            while (offset < delivery.byteCount) {
                                currentCoroutineContext().ensureActive()
                                if (!delivery.isCurrent()) throw CancellationException("Export delivery expired")
                                val chunk = delivery.copyChunk(offset, minOf(16_384, delivery.byteCount - offset))
                                    ?: throw CancellationException("Export delivery expired")
                                try { channel.writeFully(chunk); offset += chunk.size } finally { chunk.fill(0) }
                            }
                        }
                    } finally { delivery.close() }
                }
                override fun toString() = "PrivateExportContent(<redacted>)"
            })
        } catch (failure: Throwable) { delivery.close(); throw failure }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { if (!responded) respond(HttpStatusCode.NotFound) }
}
