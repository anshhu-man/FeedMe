package com.feedme.server.http

import com.feedme.contracts.CanonicalFormats
import com.feedme.contracts.WireDecodingException
import com.feedme.contracts.WireDocument
import com.feedme.contracts.WireLimits
import com.feedme.server.contract.BodyValidationResult
import com.feedme.server.contract.ContractBodyValidator
import com.feedme.server.guest.GuestBootstrapRequest
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.utils.io.ByteReadChannel
import java.nio.charset.CharacterCodingException
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Public bootstrap framing only, not installation ownership or permission to issue/replay.
 * Exact validated request bytes are returned to the caller, which owns clearing them after
 * the store call. Neither body data nor the recovery command key belongs in diagnostics. */
internal class GuestBootstrapHttpInput private constructor(
    val key: UUID,
    val contentLength: Long?,
    val mediaType: String,
) {
    override fun toString() = "GuestBootstrapHttpInput(<redacted>)"

    suspend fun readBody(channel: ByteReadChannel, validator: ContractBodyValidator): ByteArray {
        val candidate = readBoundedHttpBody(channel, GuestBootstrapRequest.MAX_BODY_BYTES,
            contentLength, ::invalidGuestBootstrap)
        var returned = false
        try {
            if (candidate.isEmpty()) invalidGuestBootstrap()
            try { WireDocument.decode(candidate, WireLimits(GuestBootstrapRequest.MAX_BODY_BYTES, 32)) }
            catch (_: WireDecodingException) { invalidGuestBootstrap() }
            if (validator.validateRequest("createGuestSession", candidate, mediaType) != BodyValidationResult.Valid)
                throw GuestHttpFailure(422, "INPUT_INVALID")
            currentCoroutineContext().ensureActive()
            // Never reconstruct JSON: whitespace, member order, Unicode and exact absence
            // remain the caller's original bytes until the dedicated store fingerprints them.
            returned = true
            return candidate
        } finally { if (!returned) candidate.fill(0) }
    }

    companion object {
        fun parse(headers: Headers, query: Parameters): GuestBootstrapHttpInput {
            if (query.names().isNotEmpty()) invalidGuestBootstrap()
            fun header(name: String): String? = headers.getAll(name)?.let { values ->
                if (values.size != 1 || values.single().any(Char::isISOControl)) invalidGuestBootstrap()
                val value = values.single()
                try { value.encodeToByteArray(throwOnInvalidSequence = true).fill(0) }
                catch (_: CharacterCodingException) { invalidGuestBootstrap() }
                value
            }
            if (header(HttpHeaders.Upgrade) != null || header("HTTP2-Settings") != null ||
                header(HttpHeaders.Connection)?.split(',')?.any { it.trim().equals("upgrade", ignoreCase = true) } == true)
                invalidGuestBootstrap()
            for (name in listOf(HttpHeaders.Authorization, "X-Device-Session", HttpHeaders.IfMatch, HttpHeaders.IfNoneMatch))
                if (header(name) != null) invalidGuestBootstrap()
            val keyText = header("Idempotency-Key") ?: invalidGuestBootstrap()
            if (!CanonicalFormats.accepts("uuid", keyText)) invalidGuestBootstrap()
            val key = UUID.fromString(keyText)
            val length = header(HttpHeaders.ContentLength)?.let { value ->
                if (!Regex("[0-9]{1,20}").matches(value)) invalidGuestBootstrap()
                value.toLongOrNull()?.takeIf { it <= GuestBootstrapRequest.MAX_BODY_BYTES } ?: invalidGuestBootstrap()
            }
            val transfer = header(HttpHeaders.TransferEncoding)
            if (transfer != null && (!transfer.equals("chunked", ignoreCase = true) || length != null)) invalidGuestBootstrap()
            val encoding = header(HttpHeaders.ContentEncoding)
            if (encoding != null && !encoding.equals("identity", ignoreCase = true)) invalidGuestBootstrap()
            val media = header(HttpHeaders.ContentType)
            if (media == null || !Regex("application/json(?:\\s*;\\s*charset\\s*=\\s*(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE).matches(media))
                throw GuestHttpFailure(400, "UNSUPPORTED_MEDIA")
            return GuestBootstrapHttpInput(key, length, media)
        }
    }
}

private fun invalidGuestBootstrap(): Nothing = throw GuestHttpFailure(400, "INVALID_REQUEST")
