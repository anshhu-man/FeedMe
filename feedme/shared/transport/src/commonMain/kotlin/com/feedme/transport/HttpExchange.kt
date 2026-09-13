package com.feedme.transport

import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.core.ports.ApiReply
import com.feedme.core.ports.FailureReason
import com.feedme.core.ports.PortResult
import com.feedme.core.ports.PrivateBytes
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException

/** Wire syntax/metadata checks are not schema, authorization, or domain validation. */
internal class HttpExchange(private val endpoint: ApiEndpoint, private val client: HttpClient) {
    suspend fun execute(call: PreparedCall, bearer: String?): PortResult<ApiReply> {
        val uncertain = call.operation.method !in setOf("GET", "HEAD", "OPTIONS")
        return try {
            // The block overload avoids Ktor's whole-response saveBody buffer. An engine may still
            // have its own buffers (notably Darwin); accepted document limit is not a native RAM cap.
            client.prepareRequest(endpoint.origin + "/" + call.pathSegments.joinToString("/") { encodeSegment(it) }) {
                method = HttpMethod.parse(call.operation.method)
                url { call.query.forEach { (name, values) -> values.forEach { parameters.append(name, it) } } }
                headers.append("Accept", "application/json, application/problem+json")
                headers.append("Accept-Encoding", "identity")
                headers.append("Cache-Control", "no-store")
                if (bearer != null) headers.append("Authorization", "Bearer $bearer")
                call.headers.forEach { (name, value) -> headers.append(name, value) }
                if (call.body != null) {
                    headers.append("Content-Type", "application/json")
                    setBody(call.body)
                }
            }.execute { response ->
                fun header(name: String): String? {
                    val values = response.headers.getAll(name) ?: return null
                    check(values.size == 1) { "Invalid response metadata" }
                    return values.single().also {
                        check(it.length <= 256 && it.isNotBlank() && it.none(Char::isISOControl)) { "Invalid response metadata" }
                    }
                }
                val status = response.status.value
                val declaration = checkNotNull(call.operation.responses[status]) { "Undeclared response" }
                check(header("Content-Encoding")?.lowercase() in setOf(null, "identity")) { "Unsupported response encoding" }
                val contentLength = header("Content-Length")?.let {
                    check(it.matches(Regex("[0-9]+"))) { "Invalid response length" }
                    checkNotNull(it.toLongOrNull()) { "Invalid response length" }
                }
                val maxBytes = WireLimits().maxBytes
                check(contentLength == null || contentLength <= maxBytes.toLong()) { "Response exceeds limit" }
                val contentType = header("Content-Type")
                if (declaration.content.isNotEmpty()) {
                    checkNotNull(contentType) { "Missing response media" }
                    check(contentType.matches(JSON_MEDIA)) { "Unsupported response media" }
                    check(contentType.substringBefore(';').trim().lowercase() in declaration.content) { "Unexpected response media" }
                } else check(contentType == null) { "Unexpected response media" }
                val retry = header("Retry-After")?.let {
                    check(it.matches(Regex("[0-9]+"))) { "Unsupported retry metadata" }
                    checkNotNull(it.toLongOrNull()?.takeIf { value -> value >= 1 }) { "Invalid retry metadata" }
                }
                val etag = header("ETag")
                val trace = header("X-Trace-Id")
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(maxBytes + 1)
                var count = 0
                while (true) {
                    val read = channel.readAvailable(buffer, count, buffer.size - count)
                    if (read == -1) { channel.closedCause?.let { throw it }; break }
                    count += read
                    check(count <= maxBytes) { "Response exceeds limit" }
                }
                check(contentLength == null || contentLength == count.toLong()) { "Incomplete response" }
                val body = if (declaration.content.isEmpty()) {
                    check(count == 0) { "Unexpected response body" }
                    null
                } else {
                    val bytes = buffer.copyOf(count)
                    WireDocument.decode(bytes)
                    PrivateBytes(bytes)
                }
                PortResult.Value(ApiReply(status, body, etag, trace, retry, contentType))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // No exception/cause/raw response escapes to logs or UI. Never infer that a write rolled
            // back merely because its receipt was unavailable. No retries or new keys here.
            PortResult.Failure(if (uncertain) FailureReason.OUTCOME_UNKNOWN else FailureReason.UNAVAILABLE)
        }
    }
}

private val JSON_MEDIA = Regex("application/(?:json|problem\\+json)(?: *; *charset *= *(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)

private fun encodeSegment(value: String): String = buildString {
    val hex = "0123456789ABCDEF"
    value.encodeToByteArray().forEach { byte ->
        val n = byte.toInt() and 255
        if (n in 65..90 || n in 97..122 || n in 48..57 || n in listOf(45, 46, 95, 126)) append(n.toChar())
        else { append('%'); append(hex[n ushr 4]); append(hex[n and 15]) }
    }
}
